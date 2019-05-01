#!/usr/bin/env groovy

def call(config) {
  def stageName = 'Checkout'

  if (!config.skipSCM) {
    try {
        notify(config, stageName, 'Pending', 'PENDING')
        checkout scm
        notify(config, stageName, 'Successful', 'PENDING')
    } catch (err) {
        echo "Caught: ${err}"
        currentBuild.result = 'UNSTABLE'
        if (isGithubError(err)) {
            notify(config, stageName, 'githubdown', 'FAILURE', true)
        } else {
            notify(config, stageName, 'Failed', 'FAILURE')
        }
        error(err.message)
    }
  }

}
