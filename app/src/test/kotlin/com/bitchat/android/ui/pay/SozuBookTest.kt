package com.bitchat.android.ui.pay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SozuBookTest {
    private val usdc = 10_000_000L
    private val fee = SozuCheque.DEMO_FEE

    @Test
    fun offlineSpendsStopAtCollateralAndTheFirstHoldStays() {
        val book = SozuBook.open(5L * usdc)
        val first = ByteArray(16) { 1 }
        val second = ByteArray(16) { 2 }
        assertEquals(Reserve.Ok, book.reserve(first, 3L * usdc, fee))
        book.attach(first, "SOZU1:one")
        assertEquals(Reserve.Collateral, book.reserve(second, 3L * usdc, fee))
        assertEquals(listOf("SOZU1:one"), book.pendingFrames())
        assertEquals(5L * usdc - 3L * usdc - fee, book.remaining())
    }

    @Test
    fun theSameNoteCannotBeReservedTwice() {
        val book = SozuBook.open(10L * usdc)
        val note = ByteArray(16) { 7 }
        assertEquals(Reserve.Ok, book.reserve(note, usdc, fee))
        assertEquals(Reserve.NoteUsed, book.reserve(note, usdc, fee))
        assertEquals(10L * usdc - usdc - fee, book.remaining())
    }

    @Test
    fun comingOnlinePushesThePendingChequeAndAReplayFails() {
        val book = SozuBook.open(5L * usdc)
        val note = ByteArray(16) { 4 }
        assertEquals(Reserve.Ok, book.reserve(note, usdc, fee))
        book.attach(note, "SOZU1:wire")
        val pending = book.pendingFrames()
        assertEquals(listOf("SOZU1:wire"), pending)

        val id = book.holdId("SOZU1:wire")
        assertTrue(id != null)
        book.apply(id!!, "settled")
        assertTrue(book.pendingFrames().isEmpty())
        assertEquals(Reserve.NoteUsed, book.reserve(note, usdc, fee))
        assertEquals(5L * usdc - usdc - fee, book.remaining())
    }

    @Test
    fun aSecondUploadOfTheSameNoteStaysSpent() {
        val book = SozuBook.open(5L * usdc)
        val note = ByteArray(16) { 9 }
        book.reserve(note, usdc, fee)
        book.attach(note, "SOZU1:wire")
        book.apply(book.holdId("SOZU1:wire")!!, "note-used")
        assertEquals(Reserve.NoteUsed, book.reserve(note, usdc, fee))
        assertEquals(5L * usdc - usdc - fee, book.remaining())
    }

    @Test
    fun waitingForThePasskeyKeepsTheOfflineHold() {
        val book = SozuBook.open(5L * usdc)
        val note = ByteArray(16) { 6 }
        book.reserve(note, usdc, fee)
        book.attach(note, "SOZU1:wire")
        book.apply(book.holdId("SOZU1:wire")!!, "passkey")
        assertEquals(listOf("SOZU1:wire"), book.pendingFrames())
        assertEquals(5L * usdc - usdc - fee, book.remaining())
        val restored = SozuBook.import(book.export())
        assertEquals(book.remaining(), restored.remaining())
    }

    @Test
    fun aCollateralRejectionGivesTheLimitBack() {
        val book = SozuBook.open(usdc)
        val note = ByteArray(16) { 3 }
        book.reserve(note, usdc / 2, fee)
        book.apply(noteKey(note)!!, "collateral")
        assertEquals(usdc, book.remaining())
        assertEquals(Reserve.Ok, book.reserve(note, usdc / 2, fee))
    }

    @Test
    fun lockingRaisesTheOfflineLimit() {
        val book = SozuBook.open(0L)
        book.lock(5L * usdc)
        assertEquals(5L * usdc, book.remaining())
        assertEquals(Reserve.Ok, book.reserve(ByteArray(16) { 1 }, usdc, fee))
        val restored = SozuBook.import(book.export())
        assertEquals(book.remaining(), restored.remaining())
    }

    @Test
    fun adoptingTheSameFaucetBalanceDoesNotStack() {
        val book = SozuBook.open(0L)
        book.adopt(10L * usdc)
        book.adopt(10L * usdc)
        book.adopt(4L * usdc)
        assertEquals(10L * usdc, book.remaining())
        assertEquals(Reserve.Ok, book.reserve(ByteArray(16) { 4 }, 9L * usdc, fee))
        assertEquals(Reserve.Collateral, book.reserve(ByteArray(16) { 5 }, usdc, fee))
    }

    @Test
    fun aSentChequeShowsInTheLedger() {
        val book = SozuBook.open(0L)
        val note = ByteArray(16) { 3 }
        book.sent(note, 2L * usdc, fee, "SOZU1:wire")
        assertEquals(1, book.payments().size)
        assertEquals(2L * usdc, book.payments().first().amount)
        assertEquals("pending", book.payments().first().state)
    }

    @Test
    fun theBookRoundTrips() {
        val book = SozuBook.open(5L * usdc)
        val note = ByteArray(16) { 8 }
        book.reserve(note, usdc, fee)
        book.attach(note, "SOZU1:wire")
        val restored = SozuBook.import(book.export())
        assertEquals(listOf("SOZU1:wire"), restored.pendingFrames())
        assertEquals(book.remaining(), restored.remaining())
    }

    @Test
    fun uploadReasons() {
        assertEquals("settled", sozuUploadReason("""{"hash":"abcdef0123456789"}""", true, "abcdef0123456789"))
        assertEquals("note-used", sozuUploadReason("""{"error":"Vault rejected","reason":"note-used"}""", false, null))
        assertEquals("collateral", sozuUploadReason("collateral short", false, null))
        assertEquals("rejected", sozuUploadReason("Vault rejected the cheque.", false, null))
        assertEquals("passkey", sozuUploadReason("""{"error":"Confirm this settlement with your passkey.","reason":"passkey"}""", false, null))
    }
}
