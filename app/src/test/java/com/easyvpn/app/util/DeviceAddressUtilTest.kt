package com.easyvpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAddressUtilTest {

    @Test
    fun `the same key always derives the same suffix`() {
        val key = "some-fake-private-key-value-for-testing"
        val a = DeviceAddressUtil.suffixForDevice(key)
        val b = DeviceAddressUtil.suffixForDevice(key)
        assertEquals(a, b)
    }

    @Test
    fun `suffix is always within the valid host range 2 to 254`() {
        // Try a spread of different inputs, not just one -- this is the property
        // that actually matters (never producing .0, .1, or .255, which are
        // conventionally reserved for network/gateway/broadcast).
        for (i in 0 until 500) {
            val suffix = DeviceAddressUtil.suffixForDevice("test-key-$i")
            assertTrue("suffix $suffix out of range for key test-key-$i", suffix in 2..254)
        }
    }

    @Test
    fun `different keys tend to derive different suffixes (not a constant function)`() {
        val suffixes = (0 until 50).map { DeviceAddressUtil.suffixForDevice("distinct-key-$it") }.toSet()
        // With 50 varied inputs hashed into a 253-wide range, seeing at least a
        // reasonable spread (not everything collapsing to 1-2 values) confirms
        // this isn't accidentally behaving like a constant function.
        assertTrue("expected reasonable spread, got only ${suffixes.size} distinct values", suffixes.size > 20)
    }

    @Test
    fun `deviceAddress combines the subnet base with the derived suffix`() {
        val key = "fixed-key-for-this-test"
        val suffix = DeviceAddressUtil.suffixForDevice(key)
        val address = DeviceAddressUtil.deviceAddress("10.8.0.0/24", key)
        assertEquals("10.8.0.$suffix", address)
    }

    @Test
    fun `deviceAddress strips a per-address CIDR down to its subnet base too`() {
        val key = "fixed-key-for-this-test"
        val fromSubnet = DeviceAddressUtil.deviceAddress("10.8.0.0/24", key)
        val fromSingleAddress = DeviceAddressUtil.deviceAddress("10.8.0.2/32", key)
        assertEquals(fromSubnet, fromSingleAddress)
    }

    @Test
    fun `deviceAddressCidr appends slash-32`() {
        val key = "fixed-key-for-this-test"
        val plain = DeviceAddressUtil.deviceAddress("10.8.0.0/24", key)
        val cidr = DeviceAddressUtil.deviceAddressCidr("10.8.0.0/24", key)
        assertEquals("$plain/32", cidr)
    }

    @Test
    fun `two different devices on the same server subnet get different addresses (the actual bug this exists to prevent)`() {
        val addressA = DeviceAddressUtil.deviceAddress("10.8.0.0/24", "device-A-private-key")
        val addressB = DeviceAddressUtil.deviceAddress("10.8.0.0/24", "device-B-private-key")
        assertTrue(
            "two different devices must not collide on the same tunnel address",
            addressA != addressB
        )
    }
}
