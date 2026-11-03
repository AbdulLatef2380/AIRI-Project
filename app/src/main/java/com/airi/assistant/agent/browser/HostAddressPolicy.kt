package com.airi.assistant.agent.browser

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Decides whether a host name or resolved address is non-public.
 *
 * Report 3, S6: the previous text-only check missed numeric IPv4 spellings
 * (`2130706433`, `0177.0.0.1`, `0x7f.0.0.1`), IPv6 private ranges (ULA,
 * link-local, IPv4-mapped) and `0.x.x.x`, and never looked at what a public
 * name actually resolves to.
 *
 * Two layers:
 *  - [isBlockedHost]: text-level check, no DNS. Anything that looks like an IP
 *    literal in any spelling is parsed and range-checked; unparsable numeric
 *    or IPv6 forms are blocked (fail closed).
 *  - [PublicOnlyDns]: for OkHttp, rejects a name if any resolved address is
 *    non-public (covers DNS rebinding and internal A records).
 *
 * Pure JVM code (java.net + OkHttp types only) so it is unit-testable.
 */
object HostAddressPolicy {

    private val BLOCKED_SUFFIXES = listOf(
        ".localhost", ".local", ".internal", ".localdomain", ".lan", ".home.arpa", ".intranet"
    )

    fun isBlockedHost(rawHost: String): Boolean {
        val host = rawHost.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return true
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare.isEmpty()) return true
        if (bare == "localhost" || BLOCKED_SUFFIXES.any { bare.endsWith(it) }) return true

        if (bare.contains(':')) {
            val bytes = parseIpv6Literal(bare) ?: return true
            return isBlockedBytes(bytes)
        }

        if (isNumericHost(bare)) {
            val bytes = parseLegacyIpv4(bare) ?: return true
            return isBlockedBytes(bytes)
        }
        return false
    }

    fun isBlockedAddress(address: InetAddress): Boolean = isBlockedBytes(address.address)

    // ── IPv4 ────────────────────────────────────────────────────────────────

    /** A host whose last label is all digits or `0x...` is read as IPv4 by inet_aton-style parsers. */
    private fun isNumericHost(host: String): Boolean {
        val last = host.substringAfterLast('.')
        if (last.isEmpty()) return true
        return last.all { it in '0'..'9' } || (last.startsWith("0x") && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** inet_aton rules: 1-4 parts; decimal, 0-prefixed octal or 0x hex; last part fills the remaining bytes. */
    private fun parseLegacyIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val values = LongArray(parts.size)
        for (i in parts.indices) {
            values[i] = parsePart(parts[i]) ?: return null
        }
        for (i in 0 until parts.size - 1) {
            if (values[i] > 255L) return null
        }
        val lastLimit = 1L shl (8 * (5 - parts.size))
        val last = values[parts.size - 1]
        if (last >= lastLimit) return null

        var packed = last
        for (i in 0 until parts.size - 1) {
            packed = packed or (values[i] shl (8 * (3 - i)))
        }
        return byteArrayOf(
            ((packed shr 24) and 0xff).toByte(),
            ((packed shr 16) and 0xff).toByte(),
            ((packed shr 8) and 0xff).toByte(),
            (packed and 0xff).toByte()
        )
    }

    private fun parsePart(part: String): Long? {
        if (part.isEmpty()) return null
        return when {
            part.startsWith("0x") -> if (part.length == 2) 0L else part.substring(2).toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.substring(1).toLongOrNull(8)
            else -> part.toLongOrNull(10)
        }
    }

    // ── IPv6 ────────────────────────────────────────────────────────────────

    private fun parseIpv6Literal(text: String): ByteArray? {
        if (text.contains('%')) return null // zone ids are never public targets
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        // A string made only of hex digits, ':' and '.' is parsed as a literal; no DNS lookup occurs.
        return try {
            InetAddress.getByName(text).address
        } catch (_: Exception) {
            null
        }
    }

    // ── Range checks (4-byte IPv4 or 16-byte IPv6) ──────────────────────────

    private fun u(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff

    private fun isBlockedBytes(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> isBlockedIpv4(u(bytes, 0), u(bytes, 1), u(bytes, 2))
        16 -> isBlockedIpv6(bytes)
        else -> true
    }

    private fun isBlockedIpv4(a: Int, b: Int, c: Int): Boolean = when {
        a == 0 -> true                              // 0.0.0.0/8
        a == 10 -> true                             // RFC1918
        a == 100 && b in 64..127 -> true            // CGNAT 100.64/10
        a == 127 -> true                            // loopback
        a == 169 && b == 254 -> true                // link-local
        a == 172 && b in 16..31 -> true             // RFC1918
        a == 192 && b == 0 && (c == 0 || c == 2) -> true // 192.0.0/24, TEST-NET-1
        a == 192 && b == 168 -> true                // RFC1918
        a == 198 && b in 18..19 -> true             // benchmarking
        a == 198 && b == 51 && c == 100 -> true     // TEST-NET-2
        a == 203 && b == 0 && c == 113 -> true      // TEST-NET-3
        a >= 224 -> true                            // multicast, reserved, broadcast
        else -> false
    }

    private fun embeddedIpv4Blocked(bytes: ByteArray, offset: Int): Boolean =
        isBlockedIpv4(u(bytes, offset), u(bytes, offset + 1), u(bytes, offset + 2))

    private fun isBlockedIpv6(b: ByteArray): Boolean {
        val firstTenZero = (0 until 10).all { b[it].toInt() == 0 }
        // :: and ::1, plus deprecated IPv4-compatible ::a.b.c.d
        if (firstTenZero && b[10].toInt() == 0 && b[11].toInt() == 0) return true
        // ::ffff:a.b.c.d (IPv4-mapped)
        if (firstTenZero && u(b, 10) == 0xff && u(b, 11) == 0xff) return embeddedIpv4Blocked(b, 12)
        // 64:ff9b::/96 (NAT64)
        if (u(b, 0) == 0x00 && u(b, 1) == 0x64 && u(b, 2) == 0xff && u(b, 3) == 0x9b &&
            (4 until 12).all { b[it].toInt() == 0 }
        ) return embeddedIpv4Blocked(b, 12)
        // 2002::/16 (6to4) embeds the IPv4 address in bytes 2..5
        if (u(b, 0) == 0x20 && u(b, 1) == 0x02) return embeddedIpv4Blocked(b, 2)
        // 2001::/32 (Teredo) and 2001:db8::/32 (documentation)
        if (u(b, 0) == 0x20 && u(b, 1) == 0x01 && u(b, 2) == 0x00 && u(b, 3) == 0x00) return true
        if (u(b, 0) == 0x20 && u(b, 1) == 0x01 && u(b, 2) == 0x0d && u(b, 3) == 0xb8) return true
        if ((u(b, 0) and 0xfe) == 0xfc) return true                       // fc00::/7 ULA
        if (u(b, 0) == 0xfe && (u(b, 1) and 0xc0) == 0x80) return true    // fe80::/10 link-local
        if (u(b, 0) == 0xfe && (u(b, 1) and 0xc0) == 0xc0) return true    // fec0::/10 site-local
        if (u(b, 0) == 0xff) return true                                  // ff00::/8 multicast
        return false
    }

    /**
     * OkHttp resolver that refuses any name resolving to a non-public address.
     * Note: OkHttp does not call a [Dns] for IP-literal hosts, so literals must
     * still pass [isBlockedHost] through the URL policy before a request.
     */
    object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (isBlockedHost(hostname)) throw UnknownHostException("Blocked non-public host")
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { isBlockedAddress(it) }) {
                throw UnknownHostException("Blocked non-public address")
            }
            return addresses
        }
    }
}
