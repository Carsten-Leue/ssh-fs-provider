package io.github.carstenleue.sshfsprovider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed representation of the imported SSH config bundle.
 * One [SshHost] per "Host" block in the OpenSSH config file.
 */
data class SshConfig(
    val hosts: List<SshHost>,
    val knownHostsContent: String? = null,
) {
    fun toJson(): String {
        val root = JSONObject()
        val hostsArr = JSONArray()
        for (host in hosts) {
            hostsArr.put(JSONObject().apply {
                put("alias", host.alias)
                put("hostname", host.hostname)
                put("port", host.port)
                put("user", host.user)
                host.identityFile?.let { put("identityFile", it) }
            })
        }
        root.put("hosts", hostsArr)
        knownHostsContent?.let { root.put("knownHosts", it) }
        return root.toString()
    }

    companion object {
        fun fromJson(json: String): SshConfig {
            val root = JSONObject(json)
            val hostsArr = root.getJSONArray("hosts")
            val hosts = (0 until hostsArr.length()).map { i ->
                val h = hostsArr.getJSONObject(i)
                SshHost(
                    alias = h.getString("alias"),
                    hostname = h.getString("hostname"),
                    port = h.getInt("port"),
                    user = h.getString("user"),
                    identityFile = h.optString("identityFile").ifEmpty { null },
                )
            }
            val knownHosts = root.optString("knownHosts").ifEmpty { null }
            return SshConfig(hosts, knownHosts)
        }
    }
}

data class SshHost(
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val user: String,
    val identityFile: String? = null,
)
