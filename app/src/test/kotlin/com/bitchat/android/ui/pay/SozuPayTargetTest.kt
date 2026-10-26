package com.bitchat.android.ui.pay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SozuPayTargetTest {
    private val account = "G" + "A".repeat(55)

    @Test
    fun nicknameBecomesTheTagWhenSkipped() {
        assertEquals("anon4821", sozuTagFromNickname("anon4821"))
        assertEquals("JoaquinNam", sozuTagFromNickname("Joaquin Nam"))
        assertTrue(sozuTagFromNickname("!!").startsWith("anon"))
    }

    @Test
    fun tagOnly() {
        assertEquals(SozuPayTarget(tag = "alice3"), parseSozuPayInput("\$alice3"))
        assertEquals(SozuPayTarget(tag = "alice3"), parseSozuPayInput("  alice3  "))
        assertNull(parseSozuPayInput("ab"))
    }

    @Test
    fun stellarAddressOnly() {
        assertEquals(SozuPayTarget(address = account), parseSozuPayInput(account.lowercase()))
        assertEquals(SozuPayTarget(address = "C" + "B".repeat(55)), parseSozuPayInput("C" + "B".repeat(55)))
    }

    @Test
    fun sozuPayQrCarriesTagAndAddress() {
        val parsed = parseSozuPayInput("sozu:pay?tag=alice3&addr=$account")
        assertEquals(SozuPayTarget(tag = "alice3", address = account), parsed)
    }

    @Test
    fun meshTextIncludesOnlyKnownFields() {
        val text = meshPayText(SozuPayTarget(tag = "alice3", address = account), 500)
        assertEquals(
            "sozu-pay\ntag:\$alice3\naddr:$account\nusdc:5.00",
            text,
        )
        assertEquals("5.00", formatCents(centsFromDigits("500")))
    }
}
