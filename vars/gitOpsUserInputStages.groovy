#!/usr/bin/env groovy

Map<String, String> selectVersionsStage(config, targetEnvironment, targetStack) {
  String SERVICE_DIR_NAME = "${WORKSPACE}/services"
  String ENV_DIR_NAME = "${WORKSPACE}/envs"
  // for each service listed in the <stackId>.yaml ask for a version to use.
  String stackFileName = "${ENV_DIR_NAME}/${targetEnvironment}/${targetStack}.yaml"
	def	stackYaml = readYaml file: stackFileName
  String[] serviceIds = stackYaml.keySet()
  def params = serviceIds.collect {
    def serviceYaml = readYaml file: "${SERVICE_DIR_NAME}/${it}.yaml"
    def serviceVersions = serviceYaml.versions.collect { 
      if (it instanceof String) {
				return it // if the version has no meta
			} else {
				return it.keySet()[0]  // if the version has meta it will be an object
			}
    }
    def choices = serviceVersions.join("\n")
    choice(name: "${it}", choices: choices, description: "${it} (current: ${stackYaml[it]})")
  }
  def selectedVersions
  stage ('Versions') {
    timeout(time: 10, unit: 'MINUTES') {
      script {
        selectedVersions = input(
          id: 'versionsInput', 
          message: "What Service version(s) should be assigned to the '${targetStack}' Stack in the '${targetEnvironment}' Environment?",
          ok: 'Next Step',
          parameters: params
        )
      }
    }
  }

  if (selectedVersions instanceof String) {
    // only 1 service in a stack will result in selectedVersions being a string instead of a Map
    selectedVersions = ["${serviceIds[0]}": selectedVersions]
  }
  
  return selectedVersions
}

Map<String, String> promoteStackStages(config, targetEnvironment, targetStack) {
  String ENV_DIR_NAME = "${WORKSPACE}/envs"
  // get origin env
  String envChoices = getEnvChoices()
  if (!envChoices || envChoices.length() == 0) {
    logger "No environments found. Ensure that /envs is not empty"
    return
  }

  String originEnvironment
  stage ("Origin Environment") {
    timeout (time: 10, unit: 'MINUTES') {
      script {
        originEnvironment = input(
          id: 'originEnvInput', 
          message: 'What Environment would you like to promote from?',
          ok: 'Next Step',
          parameters: [choice(name: 'Origin Environment', choices: envChoices)]
        ).trim()
      }

      logger "Origin Environment selected! ${originEnvironment}"
    }
  }

  // we basically just copy the origin stack to the new stack
  String originStackFileName = "${ENV_DIR_NAME}/${originEnvironment}/${targetStack}.yaml"
  def stack = readYaml file: originStackFileName
  return stack
}

String getEnvChoices() {
  String ENV_DIR_NAME = "${WORKSPACE}/envs"
  String envChoices
  dir (ENV_DIR_NAME) {
    envChoices = sh(
        script: "ls -d -- */ | sed 's/\\///g'",
        returnStdout: true
    ).trim()
  }
  return envChoices
}

def getStackChoicesForEnv(env) {
  def stackFiles = getStackFilesForEnv(env)
  return stackFiles.collect { it.getName().replace('.yaml', '') }.join("\n")
}

def getStackFilesForEnv(env) {
    String ENV_DIR_NAME = "${WORKSPACE}/envs"
    def stackFiles
    dir ("${ENV_DIR_NAME}/${env}") {
      stackFiles = findFiles(glob: "*.yaml")
    }

    if (!stackFiles || stackFiles.size() == 0) {
      def errMsg = "No stacks found. Ensure that /envs/${targetEnvironment} is not empty"
      logger errMsg
      throw new Exception(errMsg)
    }

    return stackFiles
}

def isUserInitiated() {
  return currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
}

def call(config) {
  String SERVICE_DIR_NAME = "${WORKSPACE}/services"
  def stageName = 'GitOps: User Input Stages'
  /////
  // These Stages should only run for user-initiated builds of a GitOps repo
  /////
  if (!config.gitOps || !isUserInitiated()) {
      logger "Does not appear to be a user-initiated GitOps build. Skipping '${stageName}'"
      return
  }
  if (params.gitOpsVersions != 'empty') {
    logger "This builds appears to be due to a user-initated replay. Will not ask for input."
    return
  }

  /////
  // determine target env/stack and deployment style
  /////
  def targetEnvironment
  stage ('Target Environment') {
    // get all of the envs listed in the repo

    String envChoices = getEnvChoices()
    if (!envChoices || envChoices.length() == 0) {
      logger "No environments found. Ensure that /envs is not empty"
      return
    }
    timeout (time: 10, unit: 'MINUTES') {
      script {
        targetEnvironment = input(
          id: 'targetEnvInput', 
          message: 'What Environment would you like to deploy to?',
          ok: 'Next Step',
          parameters: [choice(name: 'Target Environment', choices: envChoices)]
        ).trim()
      }

      logger "Target Environment selected! ${targetEnvironment}"
      config.internal.gitOps.TARGET_ENV = targetEnvironment
    }
  }

  String targetStack
  stage ('Stack') {
    def stackIdChoices
    try {
      stackIdChoices = getStackChoicesForEnv(targetEnvironment)
    } catch (e) {
      error(e.message)
      return
    }
    
    timeout (time: 10, unit: 'MINUTES') {
      script {
        targetStack = input(
          id: 'targetStackInput', 
          message: "Which Stack would you like to deploy to the '${targetEnvironment}' Environment?",
          ok: 'Next Step',
          parameters: [choice(name: 'Target Stack', choices: stackIdChoices)]
        )
      }

      logger "Target Stack selected! ${targetStack}"
      config.internal.gitOps.TARGET_STACK = targetStack
    }
  }
  // prompt the user to determine which style of deployment they would like to achieve.
  // we will support 2 styles first. 'version-selection' and 'environment promotion'
  String deploymentStyle
  stage ('Deployment Style') {
    timeout (time: 10, unit: 'MINUTES') {
      script {
        deploymentStyle = input(
          id: 'deploymentStyleInput', 
          message: 'What style of deployment?',
          ok: 'Next Step',
          parameters: [choice(name: 'Deployment Style', choices: 'Select Specific Versions\nPromote Stack From Another Environment')]
        )
      }

      logger "Choice selected! ${deploymentStyle}"
    }
  }

  /////
  // determine versions
  /////
  def versions
  switch (deploymentStyle) {
    case 'Select Specific Versions':
      versions = selectVersionsStage(config, targetEnvironment, targetStack)
      break
    case 'Promote Stack From Another Environment':
      versions = promoteStackStages(config, targetEnvironment, targetStack)
      break
    default:
      logger "Unable to match deployment style selection"
      break
  }
  logger "Versions Determined: ${versions}"
  config.internal.gitOps.SERVICE_VERSIONS_TO_UPDATE = versions

  // IMPORTANT! we now are ready to set config.internal.gitOps.DEPLOY = true because all deployment info has been satisfied
  config.internal.gitOps.DEPLOY = true
}