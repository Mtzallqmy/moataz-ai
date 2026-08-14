package com.mtzallqmy.aiagent.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Network layer shared by providers and remote tools.
 *
 * The default client is deliberately hostile to SSRF: loopback, link-local,
 * site-local/private, multicast, CGNAT, benchmark and unique-local IPv6
 * destinations are rejected after DNS resolution. Callers that intentionally
 * target a local service must opt in explicitly with [allowPrivateNetwork].
 */
object SafeHttpClient {
    fun create(timeoutMs: Long = 60_000L, allowPrivateNetwork: Boolean = false): OkHttpClient {
        require(timeoutMs in 1_000L..300_000L) { "HTTP timeout is outside the supported range" }
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (!allowPrivateNetwork) {
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = InetAddress.getAllByName(hostname).toList()
                    if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
                        throw UnknownHostException("Blocked private/reserved destination for $hostname")
                    }
                    return addresses
                }
            })
        }
        return builder.build()
    }

    /** Returns true for literal/private host forms without performing arbitrary hostname DNS. */
    fun isPrivate(host: String?): Boolean {
        val normalized = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        if (normalized.isBlank()) return false
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

        val looksLikeIpLiteral = ':' in normalized || normalized.all { it.isDigit() || it == '.' }
        if (!looksLikeIpLiteral) return false
        val address = runCatching { InetAddress.getByName(normalized.substringBefore('%')) }.getOrNull() ?: return true
        return isBlockedAddress(address)
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true

        return when (address) {
            is Inet4Address -> {
                val bytes = address.address.map { it.toInt() and 0xff }
                val first = bytes[0]
                val second = bytes[1]
                when {
                    // Carrier-grade NAT 100.64.0.0/10.
                    first == 100 && second in 64..127 -> true
                    // IETF protocol assignments 192.0.0.0/24.
                    first == 192 && second == 0 && bytes[2] == 0 -> true
                    // Benchmark networks 198.18.0.0/15.
                    first == 198 && second in 18..19 -> true
                    // Reserved/broadcast class E.
                    first >= 240 -> true
                    else -> false
                }
            }
            is Inet6Address -> {
                val first = address.address[0].toInt() and 0xff
                // RFC 4193 unique-local fc00::/7.
                first and 0xfe == 0xfc
            }
            else -> true
        }
    }

    /** URL normalization for user-provided HTTP(S) endpoints. */
    fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            val scheme = (uri.scheme ?: "https").lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (!uri.userInfo.isNullOrBlank()) return null
            val host = uri.host ?: return null
            if (isPrivate(host)) return null
            val authority = uri.rawAuthority ?: return null
            "$scheme://$authority${uri.rawPath ?: ""}${uri.rawQuery?.let { "?$it" } ?: ""}"
        }.getOrNull()
    }
}
