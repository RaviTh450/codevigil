package com.codepattern.standalone

import com.codepattern.lsp.PatternLanguageServer

/**
 * Unified entry point for the standalone JAR.
 *
 * Modes:
 *   --lsp          Start LSP server (stdin/stdout)
 *   --lsp-tcp PORT Start LSP server on TCP port
 *   (default)      CLI analysis mode
 */
fun main(args: Array<String>) {
    when {
        "--lsp" in args -> {
            val server = PatternLanguageServer()
            server.start(System.`in`, System.out)
        }
        "--lsp-tcp" in args -> {
            val portIndex = args.indexOf("--lsp-tcp") + 1
            val port = args.getOrNull(portIndex)?.toIntOrNull() ?: 9257
            val server = PatternLanguageServer()
            server.startOnPort(port)
        }
        else -> {
            val cli = CliRunner()
            val exitCode = cli.run(args)
            if (exitCode != 0) System.exit(exitCode)
        }
    }
}
