package io.github.carstenleue.sshfsprovider

import java.io.File
import java.util.Locale

/**
 * Parses an OpenSSH-format config file into an [SshConfig].
 *
 * Supports both space-separated and equals-separated keyword/value pairs:
 *   Host myserver
 *       HostName 192.168.1.10
 *       User alice
 *       Port 2222
 *       IdentityFile ~/.ssh/id_ed25519
 */
object SshConfigParser {

    fun parse(content: String): SshConfig {
        val hosts = mutableListOf<SshHost>()

        var currentAlias: String? = null
        var hostname: String? = null
        var user: String? = null
        var port = 22
        var identityFile: String? = null

        fun flush() {
            val alias = currentAlias ?: return
            if (alias == "*" || alias.contains("*") || alias.contains("?")) return
            val h = hostname ?: return
            hosts += SshHost(
                alias = alias,
                hostname = h,
                port = port,
                user = user ?: "root",
                identityFile = identityFile,
            )
        }

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue

            val (key, value) = parseLine(line) ?: continue

            when (key.lowercase(Locale.ROOT)) {
                "host" -> {
                    flush()
                    currentAlias = value
                    hostname = null
                    user = null
                    port = 22
                    identityFile = null
                }
                "hostname" -> hostname = value
                "user" -> user = value
                "port" -> port = value.toIntOrNull() ?: 22
                "identityfile" -> {
                    // Strip path prefix – we only need the filename, which we store
                    // alongside the config in the tgz bundle.
                    val expanded = value
                        .replace("%d", "")
                        .replace("%u", "")
                        .replace("~", "")
                    identityFile = File(expanded).name.ifEmpty { null }
                }
            }
        }
        flush()

        return SshConfig(hosts)
    }

    /**
     * Splits a config line into a (key, value) pair.
     * Supports "Key Value" and "Key=Value" formats.
     * Strips inline comments (text following " #").
     */
    private fun parseLine(line: String): Pair<String, String>? {
        val eqIdx = line.indexOf('=')
        val spIdx = line.indexOfFirst { it.isWhitespace() }

        val sepIdx = when {
            eqIdx < 0 && spIdx < 0 -> return null
            eqIdx < 0 -> spIdx
            spIdx < 0 -> eqIdx
            else -> minOf(eqIdx, spIdx)
        }

        val key = line.substring(0, sepIdx).trim()
        var value = line.substring(sepIdx + 1).trim()

        // Remove inline comment (must be preceded by a space)
        val commentIdx = value.indexOf(" #")
        if (commentIdx > 0) value = value.substring(0, commentIdx).trim()

        return if (key.isEmpty() || value.isEmpty()) null else key to value
    }
}
