package com.airi.assistant.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** Report 3, S6: numeric IPv4 spellings, IPv6 ranges and reserved blocks. */
class HostAddressPolicyTest {

    private fun blocked(host: String) = HostAddressPolicy.isBlockedHost(host)

    @Test
    fun blocksLoopbackAndPrivateIpv4InEverySpelling() {
        listOf(
            "127.0.0.1", "127.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "172.31.255.255",
            "169.254.169.254", "0.0.0.0", "0.1.2.3", "100.64.0.1",
            "2130706433",      // decimal 127.0.0.1
            "0177.0.0.1",      // octal
            "0x7f.0.0.1",      // hex
            "0x7f000001",
            "3232235777",      // 192.168.1.1
        ).forEach { assertTrue("expected blocked: $it", blocked(it)) }
    }

    @Test
    fun blocksIpv6PrivateAndMappedRanges() {
        listOf(
            "[::1]", "::1", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]",
            "[::ffff:127.0.0.1]", "[::ffff:10.0.0.1]", "[::ffff:7f00:1]",
            "[64:ff9b::7f00:1]", "[2002:7f00:1::1]", "[ff02::1]",
        ).forEach { assertTrue("expected blocked: $it", blocked(it)) }
    }

    @Test
    fun blocksLocalNamesAndFailsClosedOnGarbage() {
        listOf("localhost", "printer.local", "metadata.internal", "foo.localhost", "router.lan", "[]", "", "[fe80::1%eth0]", "[zz::1]")
            .forEach { assertTrue("expected blocked: '$it'", blocked(it)) }
    }

    @Test
    fun allowsOrdinaryPublicHosts() {
        listOf("example.com", "api.search.brave.com", "8.8.8.8", "1.1.1.1", "[2606:4700:4700::1111]", "172.32.0.1", "11.0.0.1")
            .forEach { assertFalse("expected allowed: $it", blocked(it)) }
    }

    @Test
    fun resolvedAddressesAreRangeChecked() {
        assertTrue(HostAddressPolicy.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(HostAddressPolicy.isBlockedAddress(InetAddress.getByName("192.168.0.10")))
        assertTrue(HostAddressPolicy.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue(HostAddressPolicy.isBlockedAddress(InetAddress.getByName("fd00::1")))
        assertFalse(HostAddressPolicy.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun rejectsMixedPublicAndPrivateDnsAnswersInsteadOfSelectingThePublicAddress() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("169.254.169.254")

        try {
            HostAddressPolicy.validateResolvedAddresses("attacker.example", listOf(public, private))
            throw AssertionError("mixed DNS answer must fail closed")
        } catch (_: UnknownHostException) {
            // Expected: a single private answer blocks the whole host resolution.
        }
        assertTrue(HostAddressPolicy.validateResolvedAddresses("attacker.example", listOf(public)).single() == public)
    }

    @Test
    fun rejectsNumericAndObfuscatedMetadataTargets() {
        listOf(
            "169.254.169.254", "2852039166", "0xa9fea9fe", "0251.0376.0251.0376",
            "[fe80::a9fe:a9fe]", "metadata.google.internal", "metadata.azure.internal",
        ).forEach { assertTrue("expected SSRF target blocked: $it", blocked(it)) }
    }
}
