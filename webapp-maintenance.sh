#!/bin/bash -xe

# A Jenkins job that periodically runs some cleanup tasks on
# our webapp.
#
# Here are some of the cleanups we run:
#   deploy/pngcrush.py
#       compress images
#   clean up obsolete containers in /var/lib/docker
#   TODO: khan-exercises/local-only/update_local.sh
#       get khan-exercises matching webpp
#   TODO: vacuum unused indexes
#   TODO: store test times to use with the @tiny/@small/@large decorators
#   TODO: remove obsolete webapp deploy-branches on github
#
# There are also some cleanups we'd like to run but probably can't
# because they require manual intervention:
#   tools/find_nltext_in_js
#       find places in .js files that need $._(...)
#   tools/list_unused_images
#       find images we can delete from the repo entirely
#   tools/list_unused_api_calls.py
#       find /api/... routes we can delete from the repo entirely
#   deploy/list_files_uploaded_to_appengine
#       find files we can add to skip_files.yaml
#   clean up translations that download_i18n.py's linter complains about
#   clean up the bottom of lint_blacklist.txt
#   move not-commonly-used s3 data to glacier

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

cd "$WEBSITE_ROOT"

safe_pull .

pngcrush() {
    deploy/pngcrush.py
    {
        echo "Automatic compression of webapp images via $0"
        echo
        echo "| size % | old size | new size | filename"
        git status --porcelain | sort | while read status filename; do
            old_size=`git show HEAD:"$filename" | wc -c`
            new_size=`cat "$filename" | wc -c`
            ratio=`expr $new_size \* 100 / $old_size`
            echo "| $ratio% | $old_size | $new_size | $filename"
        done
    } | safe_commit_and_push . -a -F -
}

# Every week, make sure that /var/lib/docker's size is under control.
# We then re-do a docker run to re-create the docker images we
# actually still need (to make the next deploy faster).
clean_docker() {
    docker rm `docker ps -a | grep Exited | cut -f1 -d" "` || true
    docker rmi `docker images -aq` || true
    make docker-prod-staging-dir
}

# Every week, we do a 'partial' clean of genfiles directories that
# gets rid of certain files that are "probably" obsolete.
clean_genfiles() {
    for dir in $HOME/jobs/*/workspace/webapp/genfiles; do
        (
        echo "Cleaning genfiles in $dir"
        cd "$dir"

        # This means that slow-changing languages may get nuked even
        # though they're up to date, but we'll just redownload them so
        # no harm done.
        find translations/pofiles -mtime +7 -print0 | xargs -0r rm -v
        find translations/approved_pofiles -mtime +7 -print0 | xargs -0r rm -v
        )
    done
}

# Clean up some gcs directories that have too-complicated cleanup
# rules to use the gcs lifecycle rules.
clean_ka_translations() {
    for dir in `gsutil ls gs://ka_translations`; do
        versions=`gsutil ls $dir | sort`
        # We keep all version-dirs that fit either of these criteria:
        # 1) One of the last 3 versions
        # 2) version was created within the last week
        not_last_three=`echo "$versions" | tac | tail -n+4`
        week_ago_time_t=`date -d "-7 days" +%s`
        for version in $not_last_three; do
            # `basename $version` looks like "2016-04-17-2329",
            # but `date` wants "2016-04-17 23:29".
            date="`basename "$version" | cut -b1-10` `basename "$version" | cut -b12-13`:`basename "$version" | cut -b14-15`"
            time_t=`date -d "$date" +%s`
            if [ "$time_t" -lt "$week_ago_time_t" ]; then
                # Very basic sanity-check: never delete files from today!
                if echo "$version" | grep -q `date +%Y-%m-%d-`; then
                    echo "FATAL ERROR: Why are we trying to delete $version??"
                    exit 1
                fi
                echo "Deleting obsolete directory $version"
                # TODO(csilvers): make it 'gsutil -m' after we've debugged
                # why that sometimes fails with 'file not found'.
                gsutil rm -r "$version"
            fi
        done
    done
}

clean_ka_static() {
    # First we find the manifest files for the last week.  (Plus we
    # always keep the last 3.)  We'll keep any file listed in there.
    week_ago_time_t=`date -d "-7 days" +%s`
    manifests_seen=0
    files_to_keep=`mktemp -d`/files_to_keep
    # The 'ls -l' output looks like this:
    #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
    gsutil ls -l 'gs://ka-static/_manifest.*' | grep _manifest | sort -k2r | while read line; do
        date=`echo "$line" | awk '{print $2}'`
        time_t=`date -d "$date" +%s`
        if [ "$time_t" -gt "$week_ago_time_t" -o $manifests_seen -lt 3 ]; then
            # (Since we create the manifest-files, we know they don't
            # have spaces in their name.)
            manifest=`echo "$line" | awk '{print $3}'`
            # This gets the keys (which is the url) to each
            # dict-entry in the manifest file.
            gsutil cat "$manifest" | grep -o '"[^"]*":' | tr -d '":' \
                >> "$files_to_keep"
            # We also keep the manifest-file itself around!
            echo "$manifest" >> "$files_to_keep"
            manifests_seen=`expr $manifests_seen + 1`
        fi
    done

    # We need to add the gs://ka-static prefix to match the gsutil ls output.
    LANG=C sort -u "$files_to_keep" | sed s,^/,gs://ka-static/, \
        > "$files_to_keep.sorted"

    # Basic sanity check: make sure favicon.ico is in the list of files
    # to keep.  If not, something has gone terribly wrong.
    if ! grep -q "favicon.ico" "$files_to_keep.sorted"; then
        echo "FATAL ERROR: The list of files-to-keep seems to be wrong"
        exit 1
    fi

    # Now we go through every file in ka-static and delete it if it's
    # not in files-to-keep.  We ignore lines ending with ':' -- those
    # are directories.
    # TODO(csilvers): make the xargs 'gsutil -m' after we've debugged
    # why that sometimes fails with 'file not found'.
    gsutil -m ls -r gs://ka-static/ \
        | grep . \
        | grep -v ':$' \
        | LANG=C sort > "$files_to_keep.candidates"
    # This prints files in 'candidates that are *not* in files_to_keep.
    LANG=C comm -23 "$files_to_keep.candidates" "$files_to_keep.sorted" \
        | tr '\012' '\0' \
        | xargs -0r gsutil rm
}

clean_docker
clean_genfiles
clean_ka_translations
clean_ka_static
pngcrush
