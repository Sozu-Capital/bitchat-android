package com.bitchat.android.ui.pay

/**
 * Who an offline payment is for. A typed field can only hold a Sozu tag.
 * A Stellar address arrives from paste or from a Sozu QR (`sozu:pay?tag=&addr=`).
 */
data class SozuPayTarget(
    val tag: String? = null,
    val address: String? = null,
) {
    fun canPay(): Boolean = tag != null || address != null
}

private val CLASSIC_G = Regex("^G[A-Z0-9]{55}$")
private val CONTRACT_C = Regex("^C[A-Z0-9]{55}$")
private val TAG = Regex("^[a-zA-Z0-9_]{3,30}$")

fun normalizeSozuTag(raw: String): String {
    return raw.trim().replace(Regex("^\\$+"), "").replace(Regex("[^a-zA-Z0-9_]"), "")
}

fun isValidSozuTag(tag: String): Boolean = TAG.matches(tag)

/** Bitchat's default name (`anon1234`) is already a valid tag. Anything else is cleaned. */
fun sozuTagFromNickname(raw: String): String {
    val clean = normalizeSozuTag(raw)
    if (isValidSozuTag(clean)) return clean
    return "anon${(1000..9999).random()}"
}

fun isValidStellarAddress(value: String): Boolean {
    val trimmed = value.trim().uppercase()
    return CLASSIC_G.matches(trimmed) || CONTRACT_C.matches(trimmed)
}

fun centsFromDigits(digits: String): Int {
    val clean = digits.filter { it.isDigit() }.take(8)
    return clean.toIntOrNull() ?: 0
}

/** USDC minor units (7 decimals) shown as dollars.cents. 100_000 minor is one cent. */
fun formatMinorUnits(minor: Long): String {
    if (minor <= 0L) return "0.00"
    val cents = (minor / 100_000L).coerceAtMost(Int.MAX_VALUE.toLong())
    return formatCents(cents.toInt())
}

fun formatCents(cents: Int): String {
    val abs = kotlin.math.abs(cents)
    val whole = abs / 100
    val frac = (abs % 100).toString().padStart(2, '0')
    return "$whole.$frac"
}

/**
 * Accepts a Sozu tag, a Stellar address, or the deposit QR payload
 * `sozu:pay?tag=alice&addr=G...`.
 */
fun parseSozuPayInput(raw: String): SozuPayTarget? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("sozu:pay?")) {
        val params = queryParams(trimmed.removePrefix("sozu:pay?"))
        val tag = params["tag"]?.let(::normalizeSozuTag)?.takeIf(::isValidSozuTag)
        val address = params["addr"]?.trim()?.uppercase()?.takeIf(::isValidStellarAddress)
        if (tag == null && address == null) return null
        return SozuPayTarget(tag = tag, address = address)
    }

    val address = trimmed.uppercase().takeIf(::isValidStellarAddress)
    if (address != null) return SozuPayTarget(address = address)

    val tag = normalizeSozuTag(trimmed).takeIf(::isValidSozuTag)
    if (tag != null) return SozuPayTarget(tag = tag)
    return null
}

fun meshPayText(target: SozuPayTarget, cents: Int): String {
    val lines = mutableListOf("sozu-pay")
    target.tag?.let { lines += "tag:$$it" }
    target.address?.let { lines += "addr:$it" }
    lines += "usdc:${formatCents(cents)}"
    return lines.joinToString("\n")
}

data class SozuWalletLink(
    val address: String,
    val tag: String?,
    val minor: Long,
)

/**
 * `sozu://link?addr=C...&usdc=10` is the web passkey wallet.
 * `usdc` is the faucet balance already on that account, in whole USDC.
 */
fun parseSozuLink(raw: String): SozuWalletLink? {
    val trimmed = raw.trim()
    val query = when {
        trimmed.startsWith("sozu://link?") -> trimmed.removePrefix("sozu://link?")
        trimmed.startsWith("sozu:link?") -> trimmed.removePrefix("sozu:link?")
        else -> return null
    }
    val params = queryParams(query.substringBefore('#'))
    val address = params["addr"]?.trim()?.uppercase()?.takeIf(::isValidStellarAddress) ?: return null
    val tag = params["tag"]?.let(::normalizeSozuTag)?.takeIf(::isValidSozuTag)
    val minor = params["usdc"]?.let(::parseUsdcMinor) ?: 0L
    return SozuWalletLink(address, tag, minor)
}

/** Whole USDC with up to 7 decimal places, as minor units. */
fun parseUsdcMinor(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty() || text.count { it == '.' } > 1) return null
    val parts = text.split('.')
    if (parts[0].isEmpty() || parts[0].any { !it.isDigit() }) return null
    val whole = parts[0].toLongOrNull() ?: return null
    if (whole > Long.MAX_VALUE / 10_000_000L) return null
    val fracText = if (parts.size == 2) parts[1] else ""
    if (fracText.any { !it.isDigit() }) return null
    val frac = fracText.padEnd(7, '0').take(7).toLongOrNull() ?: return null
    return whole * 10_000_000L + frac
}

fun shortAddress(address: String): String {
    if (address.length <= 12) return address
    return address.take(4) + "…" + address.takeLast(4)
}

private fun queryParams(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    return query.split("&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val key = decode(part.substring(0, eq))
        val value = decode(part.substring(eq + 1))
        key to value
    }.toMap()
}

private fun decode(value: String): String {
    return java.net.URLDecoder.decode(value, "UTF-8")
}
