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
