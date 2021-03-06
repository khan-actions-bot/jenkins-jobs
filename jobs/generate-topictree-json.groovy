// This job generates topic tree json for all locales and writes the 
// generated files to GCS which are used by api '/api/v1/topictree'
// for its response.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """The name of a webapp branch to use when running generate topictree 
	json script. Most of the time master (the default) is the correct choice.
	The main reason to use a different branch is to test changes to the  
	generate topictree json process that haven't yet been merged to master.""",
    "master"

).addStringParam(
        "LOCALE",
	    """The locale for which to run this job for.""",
	    ""
).addCronSchedule("H H * * *"

).apply();

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);

    dir("webapp") {
        sh("make clean_pyc");
        sh("make -B deps");  // force a remake of all deps all the time
    }

    lock("using-a-lot-of-memory") {
        withSecrets() {
            sh("jenkins-jobs/run-topictree-gen.sh ${params.LOCALE}");
        }
    }
}

onMaster('6h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}

