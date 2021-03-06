#!/usr/bin/env groovy
import com.github.banzaicicd.cfg.BanzaiCfg

def call(BanzaiCfg cfg, vulnerabilityCfg) {
    def streamName = vulnerabilityCfg.streamName ?: "${cfg.appName}_${env.BRANCH_NAME}"
    def buildCmd
    if (cfg.userData?.coverity?.buildCmd) {
      logger "Coverity buildCmd detected in UserData"
      buildCmd = cfg.userData.coverity.buildCmd
    } else {
      buildCmd = vulnerabilityCfg.buildCmd
    }
    def iDir = "${env.WORKSPACE}/idir"
    
    // check for all required vulnerabilityCfg
    def requiredOpts = ['serverHost', 'serverPort', 'toolId', 'credId', 'projectName']
    def failedRequiredOpts = requiredOpts.findAll { !vulnerabilityCfg[it] }
    if (!buildCmd) {
        failedRequiredOpts.add('buildCmd or env.BUILD_CMD')
    }
    if (failedRequiredOpts.size() > 0) {
        def isOrAre = failedRequiredOpts.size() > 1 ? 'are' : 'is'
        error("${failedRequiredOpts.join(', ')} ${isOrAre} required for Coverity")
        return
    }

    // Clear the intermediate directory if it already exists from a previous run
    sh "if [ -e ${iDir} ]; then rm -rf ${iDir} ; fi"

    // wrap Coverity Env
    withCoverityEnvironment(coverityInstanceUrl: "https://${vulnerabilityCfg.serverHost}:${vulnerabilityCfg.serverPort}", projectName: vulnerabilityCfg.projectName, streamName: streamName, viewName: '') {
      withCredentials([file(credentialsId: vulnerabilityCfg.credId, variable: 'CRED_FILE')]) {
        def credParams = "--on-new-cert trust --auth-key-file ${CRED_FILE}"
        def hostAndPort = "--host ${vulnerabilityCfg.serverHost} --port ${vulnerabilityCfg.serverPort}"
        // We have to first check and see if the stream exists since synopsys_coverity step doesn't allow us to react to cmd feedback.
        // 1. check for the existence of the stream 
        //def listStreamsCmd = "unset https_proxy && cov-manage-im --mode streams --show --name ${COV_STREAM} --url ${COV_URL} --ssl ${credParams} | grep ${COV_STREAM}"
        def httpsProxy
        if (cfg.proxy) {
          httpsProxy = "https://${cfg.proxy.toString()}"
        }
        def listStreamsCmd =
        """
          export https_proxy=${httpsProxy} && export no_proxy=${cfg.noProxy} \
          && cov-manage-im --mode streams --show --name  ${COV_STREAM} ${hostAndPort} --ssl ${credParams}
        """
        def streamList
        try { // have to wrap this because a negative result by cov-manage-im is returned as a shell exit code of 1. awesome TODO, figure out how to get jenkins to ignore this failure in Blue Ocean
          streamList = sh (
            script: listStreamsCmd,
            returnStdout: true,
            returnStatus: false
          ).trim()
        } catch (Throwable e) {
          logger "Stream '${streamName}' was not found on the Coverity server"
        }
        
        def addStream = false
        if (!streamList || !streamList.contains(streamName)) {
          addStream = true
        } else {
          logger "${COV_STREAM} already exists"
        }

        // 2. run the remaining commsnds 
        def commands = []
        if (addStream) {
          def covAddStreamCmd = 
          """
            export https_proxy=${httpsProxy} && export no_proxy=${cfg.noProxy} \
            && cov-manage-im --mode streams --add --set name:${COV_STREAM} --set lang:mixed ${credParams} ${hostAndPort} --ssl
          """
          def covBindStreamCmd = 
          """
            export https_proxy=${httpsProxy} && export no_proxy=${cfg.noProxy} \
            && cov-manage-im --mode projects --name ${COV_PROJECT} --update --insert stream:${COV_STREAM} ${credParams} ${hostAndPort} --ssl
          """
          commands.addAll([covAddStreamCmd, covBindStreamCmd])
        }

        def covBuildCmd = "cov-build --dir ${iDir} ${buildCmd}"
        def covAnalyzeCmd = "cov-analyze --dir ${iDir}"
        def covCommitCmd = """
          export https_proxy=${httpsProxy} && export no_proxy=${cfg.noProxy} \
          && cov-commit-defects --dir ${iDir} --stream ${COV_STREAM} ${credParams} --url ${COV_URL}
        """
        commands.addAll([covBuildCmd, covAnalyzeCmd, covCommitCmd])

        // run each command
        commands.each {
          sh it
        }
        
      } // with
    }
}