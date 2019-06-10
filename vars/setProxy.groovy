#!/usr/bin/env groovy

def call(config) {
    // set proxy info from Environment if applicable
    if (config.httpsProxy && config.httpsProxy.envVar) {
        logger "Setting HTTPS Proxy from environment variable ${config.httpsProxy.envVar}"
        def hostAndPort = env[config.httpsProxy.envVar].tokenize(":")
        config.httpsProxy = [
            host: hostAndPort[0],
            port: hostAndPort[1]
        ]
        config.noProxy = config.noProxy ?: env.no_proxy
    }
    if (config.httpProxy && config.httpProxy.envVar) {
        logger "Setting HTTP Proxy from environment variable ${config.httpProxy.envVar}"
        def hostAndPort = env[config.httpProxy.envVar].tokenize(":")
        config.httpProxy = [
            host: hostAndPort[0],
            port: hostAndPort[1]
        ]
        config.noProxy = env.no_proxy
        config.noProxy = config.noProxy ?: env.no_proxy
    }

    if (config.httpsProxy) {
        logger "HTTPS PROXY set to ${config.httpsProxy.host}:${config.httpsProxy.port}"
    }

    if (config.httpProxy) {
        logger "HTTP PROXY set to ${config.httpProxy.host}:${config.httpProxy.port}"
    }

    if (config.httpsProxy || config.httpProxy) {
        logger "NO PROXY set to ${config.noProxy}"
    }
}