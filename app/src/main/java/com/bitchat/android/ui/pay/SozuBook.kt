package com.bitchat.android.ui.pay

import android.content.Context

/**
 * Offline collateral for one account.
 * A cheque is reserved before it is signed. The same note cannot be reserved twice.
 * Amount plus fee cannot pass the remaining collateral. Chain settlement keeps the hold.
 * A chain reply of note-used keeps it too: the note was spent by the first upload.
 * Waiting for the passkey keeps the hold. The amount stays reserved until the vault accepts it.
 * Any other rejection gives the collateral back.
 */
class SozuBook(
    var collateral: Long,
    private val holds: MutableList<Hold> = mutableListOf(),
) {
    fun remaining(): Long = (collateral - holds.sumOf { it.amount + it.fee }).coerceAtLeast(0L)

    /** Adds to the offline limit stored on this phone. This does not move USDC on chain. */
    fun lock(amount: Long) {
        if (amount <= 0L) return
        collateral += amount
    }

    /**
     * Raises the offline limit to a faucet balance already on the linked wallet.
     * Opening the same link again does not add the amount a second time.
     * This does not move USDC on chain.
     */
    fun adopt(amount: Long) {
        if (amount > collateral) collateral = amount
    }

    fun reserve(noteId: ByteArray, amount: Long, fee: Long): Reserve {
        val id = noteKey(noteId) ?: return Reserve.Amount
        if (amount <= 0L || fee < 0L || amount > Long.MAX_VALUE - fee) return Reserve.Amount
        if (holds.any { it.id == id }) return Reserve.NoteUsed
        if (amount + fee > remaining()) return Reserve.Collateral
        holds.add(Hold(id, amount, fee, null, PENDING))
        return Reserve.Ok
    }

    /** Records a signed cheque so it shows in the ledger even when the offline cap is unset. */
    fun sent(noteId: ByteArray, amount: Long, fee: Long, frame: String) {
        val id = noteKey(noteId) ?: return
        val index = holds.indexOfFirst { it.id == id }
        if (index < 0) {
            holds.add(Hold(id, amount, fee, frame, PENDING))
        } else {
            holds[index] = holds[index].copy(amount = amount, fee = fee, frame = frame)
        }
    }

    fun attach(noteId: ByteArray, frame: String) {
        val id = noteKey(noteId) ?: return
        val index = holds.indexOfFirst { it.id == id && it.state == PENDING }
        if (index < 0) return
        holds[index] = holds[index].copy(frame = frame)
    }

    fun release(noteId: ByteArray) {
        val id = noteKey(noteId) ?: return
        holds.removeAll { it.id == id && it.state == PENDING }
    }

    fun holdId(frame: String): String? = holds.firstOrNull { it.frame == frame }?.id

    fun payments(): List<Hold> = holds.toList()

    fun pendingFrames(): List<String> = holds.mapNotNull { hold ->
        hold.frame?.takeIf { hold.state == PENDING }
    }

    fun apply(id: String, reason: String) {
        val index = holds.indexOfFirst { it.id == id }
        if (index < 0) return
        when (reason) {
            "settled" -> holds[index] = holds[index].copy(state = SETTLED)
            "note-used" -> holds[index] = holds[index].copy(state = USED)
            "passkey" -> Unit
            else -> holds.removeAt(index)
        }
    }

    fun export(): String {
        val lines = ArrayList<String>(holds.size + 2)
        lines.add("v1")
        lines.add(collateral.toString())
        for (hold in holds) {
            lines.add(listOf(hold.id, hold.amount.toString(), hold.fee.toString(), hold.state, hold.frame.orEmpty()).joinToString("|"))
        }
        return lines.joinToString("\n")
    }

    companion object {
        fun open(collateral: Long): SozuBook = SozuBook(collateral.coerceAtLeast(0L))

        fun import(raw: String?): SozuBook {
            val lines = raw?.lineSequence()?.toList().orEmpty()
            if (lines.size < 2 || lines[0] != "v1") return open(0L)
            val collateral = lines[1].toLongOrNull() ?: return open(0L)
            val book = open(collateral)
            for (line in lines.drop(2)) {
                val parts = line.split("|", limit = 5)
                if (parts.size != 5) continue
                val amount = parts[1].toLongOrNull() ?: continue
                val fee = parts[2].toLongOrNull() ?: continue
                val state = parts[3]
                if (state != PENDING && state != SETTLED && state != USED) continue
                if (book.holds.any { it.id == parts[0] }) continue
                book.holds.add(Hold(parts[0], amount, fee, parts[4].ifEmpty { null }, state))
            }
            return book
        }
    }
}

data class Hold(
    val id: String,
    val amount: Long,
    val fee: Long,
    val frame: String?,
    val state: String,
)

sealed class Reserve {
    data object Ok : Reserve()
    data object Collateral : Reserve()
    data object NoteUsed : Reserve()
    data object Amount : Reserve()
}

private const val PENDING = "pending"
private const val SETTLED = "settled"
private const val USED = "used"

fun noteKey(noteId: ByteArray): String? {
    if (noteId.size != 16) return null
    return noteId.joinToString("") { "%02x".format(it) }
}

/** What an upload reply means. Settled and note-used leave the queue. Collateral does too. */
fun sozuUploadReason(body: String, ok: Boolean, hash: String?): String {
    val lower = body.lowercase()
    val tagged = REASON.find(lower)?.groupValues?.get(1)
    if (tagged == "note-used" || tagged == "collateral" || tagged == "passkey") return tagged
    if (lower.contains("note-used") || lower.contains("note_used") || lower.contains("note already used")) {
        return "note-used"
    }
    if (lower.contains("collateral")) return "collateral"
    if (ok && hash != null && hash.length >= 16) return "settled"
    return "rejected"
}

private val REASON = Regex("\"reason\"\\s*:\\s*\"(note-used|collateral|settled|rejected|passkey)\"")

data class SozuLedgerRow(
    val id: String,
    val amount: Long,
    val incoming: Boolean,
    val who: String,
    val state: String,
)

object SozuInbox {
    fun remember(context: Context, frame: String, ownerAddress: String): Boolean {
        val view = SozuCheque.read(frame) ?: return false
        val mine = StellarStrKey.accountId(ownerAddress) ?: return false
        if (!view.payee.contentEquals(mine) || view.owner.contentEquals(mine)) return false
        val who = StellarStrKey.encodeContract(view.owner)
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val current = prefs.getString("inbox", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toMutableList()
        if (current.any { it.startsWith("${view.noteId}|") }) return false
        current.add(0, "${view.noteId}|${view.amount}|$who|pending")
        prefs.edit().putString("inbox", current.take(40).joinToString("\n")).apply()
        return true
    }

    fun list(context: Context): List<SozuLedgerRow> {
        val raw = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE).getString("inbox", "").orEmpty()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val amount = parts[1].toLongOrNull() ?: return@mapNotNull null
            SozuLedgerRow(parts[0], amount, true, parts[2], parts[3])
        }.toList()
    }
}

object SozuLedger {
    fun rows(context: Context, book: SozuBook, owner: String): List<SozuLedgerRow> {
        val outgoing = book.payments().asReversed().map { hold ->
            val who = hold.frame?.let(SozuCheque::read)?.let { StellarStrKey.encodeContract(it.payee) }.orEmpty()
            SozuLedgerRow(hold.id, hold.amount, false, who, hold.state)
        }
        val seen = outgoing.map { it.id }.toSet()
        return outgoing + SozuInbox.list(context).filter { it.id !in seen }
    }

    fun load(context: Context): SozuBook {
        val raw = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE).getString("book", null)
        return SozuBook.import(raw)
    }

    fun save(context: Context, book: SozuBook) {
        context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
            .edit()
            .putString("book", book.export())
            .apply()
    }
}
