// We use these user-defined steps from vars/:
//import vars.exec
//import vars.onMaster
//import vars.withSecrets


// True if our status matches one of the statuses in the `when` list.
def _shouldReport(status, when) {
   // We check if our status is one we want to report on.  One special
   // case: if we are asked to report on success, we also report when
   // we are BACK TO NORMAL, since that is a special case of success.
   return status in when || (status == "BACK TO NORMAL" && "SUCCESS" in when);
}


// True if the status code is one of the ones that indicates failure.
def _failed(status) {
   return status in ['FAILURE', 'UNSTABLE', 'ABORTED', 'NOT_BUILT'];
}


// Number of jobs that have failed in a row, including this one.
def _numConsecutiveFailures(status) {
   def numFailures = 0;
   def build = currentBuild;    // a global provided by jenkins
   while (build && _failed(build.result)) {
      numFailures++;
      build = build.previousBuild;
   }
   return numFailures;
}


def _ordinal(num) {
   if (num % 100 == 11 || num % 100 == 12 || num % 100 == 13) {
      return num.toString() + "th";
   }
   def suffix = ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"];
   return num.toString() + suffix[num % 10];
}


def _statusText(status) {
   if (status == "FAILURE") {
      def numFailures = _numConsecutiveFailures(status);
      if (numFailures > 1) {
         return "failed (${_ordinal(numFailures)} time in a row)";
      }
      return "failed";
   } else if (status == "UNSTABLE") {
      return "is unstable";
   } else if (status == "ABORTED") {
      return "was aborted";
   } else if (status == "NOT_BUILT") {
      return "was not built";
   } else if (status == "SUCCESS") {
      return "succeeded";
   } else if (status == "BUILD START") {
      return "is starting";
   } else if (status == "BACK TO NORMAL") {
      return "is back to normal";
   } else {
      return "has unknown status (oops!)";
   }
}


// Returns the last 50 lines of the logfile.  However, we omit the
// text of commands run by notify() (below) when it's easy to do so,
// since they are run after the job proper has already finished, and
// don't provide any useful information for debugging.
def _logSuffix() {
   def NUM_LINES = 50;
   // We allow 400 lines of post-failure processing.
   // TODO(csilvers): rawBuild is a bit of a security hole; revoke
   // permissions for it and use logMatcher instead if we can get it
   // to work.
   def loglines = currentBuild.rawBuild.getLog(NUM_LINES + 400);

   // We always include loglines[0], that's the one that says
   def end = loglines.size() - 1;
   if (end < 1) {
      return "";
   }
   for (def i = 0; i < loglines.size(); i++) {
      if ("===== JOB FAILED =====" in loglines[i]) {
         end = i;
         break;
      }
   }
   def start = end - NUM_LINES < 1 ? 1 : end - NUM_LINES;
   return loglines[0, start..end].join("\n");
}


// Supported options:
// channel (required): what slack channel to send to
// when (required): under what circumstances to send to jenkins; a list.
//    Possible values are SUCCESS, FAILURE, UNSTABLE, or BACK TO NORMAL.
// sender: the name to use for the bot message (e.g. "Jenny Jenkins")
// emoji: the emoji to use for the bot (e.g. ":crocodile:")
def sendToSlack(slackOptions, status) {
   def msg = ("${env.JOB_NAME} ${currentBuild.displayName} " +
              "${_statusText(status)} (<${env.BUILD_URL}|Open>)");
   def severity = _failed(status) ? 'error' : 'info';
   onMaster("1m") {
      withSecrets() {     // you need secrets to talk to slack
         sh("echo ${exec.shellEscape(msg)} | " +
            "jenkins-tools/alertlib/alert.py " +
            "--slack=${exec.shellEscape(slackOptions.channel)} " +
            // TODO(csilvers): make success green, not gray.
            "--severity=${severity} " +
            "--chat-sender=${exec.shellEscape(slackOptions.sender ?: 'Janet Jenkins')} " +
            "--icon-emoji=${exec.shellEscape(slackOptions.emoji ?: ':crocodile:')}");
      }
   }
}

// Supported options:
// to (required): a string saying who to send mail to.  We automatically
//    append "@khanacademy.org" to each email address in the list.
//    If you want to send to multiple people, use a comma: "sal, team".
// cc: a string saying who to cc on the email.  Format is the same as
//    for `to`.
def sendToEmail(emailOptions, status) {
   def severity = _failed(status) ? 'error' : 'info';
   def subject = ("${env.JOB_NAME} ${currentBuild.displayName} " +
                  "${_statusText(status)}");
   def body = ("""${subject}: See ${env.BUILD_URL} for full details.

Below is the tail of the build log.
If there's a failure it is probably near the bottom!

---------------------------------------------------------------------

${_logSuffix()}
""");

   onMaster("1m") {
      sh("echo ${exec.shellEscape(body)} | " +
         "jenkins-tools/alertlib/alert.py " +
         "--mail=${exec.shellEscape(emailOptions.to)} " +
         "--summary=${exec.shellEscape(subject)} " +
         "--cc=${exec.shellEscape(emailOptions.cc ?: '')} " +
         "--sender-suffix=${exec.shellEscape(env.JOB_NAME.replace(' ', '_'))} " +
         "--severity=${severity}");
   }
}


def call(options, Closure body) {
   def abortState = [complete: false, aborted: false];

   currentBuild.result = "SUCCESS";
   try {
      if (options.slack && "BUILD START" in options.slack.when) {
         sendToSlack(options.slack, "BUILD START");
      }

      // We do this `parallel` to catch when the job has been aborted.
      // http://stackoverflow.com/questions/36855066/how-to-query-jenkins-to-determine-if-a-still-building-pipeline-job-has-been-abor
      parallel(
         "_watchdog": {
            try {
               waitUntil({ abortState.complete || abortState.aborted });
            } catch (e) {
               if (!abortState.complete) {
                  abortState.aborted = true;
               }
               throw e
            } finally {
               abortState.complete = true;
            }
         },
         "body": {
            try {
               body();
            } finally {
               abortState.complete = true;
            }
         },
         "failFast": true,
      );
   } catch (e) {
      if (abortState.aborted) {
         currentBuild.result = "ABORTED";
         ansiColor('xterm') {
            echo("\033[1;33m===== JOB ABORTED =====\033[0m");
         }
      } else {
         currentBuild.result = "FAILED";
         // Log a message to help us ignore this post-build action when
         // analyzing the logs for errors.
         ansiColor('xterm') {
            echo("\033[1;33m===== JOB FAILED =====\033[0m");
         }
      }
      throw e;
   } finally {
      def status = currentBuild.result;

      // If we are success and the previous build was a failure, then
      // we change the status to BACK TO NORMAL.
      if (status == "SUCCESS" && currentBuild.previousBuild &&
          _failed(currentBuild.previousBuild.result)) {
         status = "BACK TO NORMAL";
      }

      if (options.slack && _shouldReport(status, options.slack.when)) {
         // Make sure the user hasn't already sent to slack.
         if (!env.SENT_TO_SLACK) {
            sendToSlack(options.slack, status);
         }
      }
      if (options.email && _shouldReport(status, options.email.when)) {
         sendToEmail(options.email, status);
      }
   }
}
