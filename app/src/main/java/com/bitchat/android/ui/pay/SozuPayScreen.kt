package com.bitchat.android.ui.pay

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SozuPayScreen(
    onSend: (String, (Boolean) -> Unit) -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val camera = rememberPermissionState(android.Manifest.permission.CAMERA)
    var digits by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val cents = centsFromDigits(digits)
    val validTag = tag.takeIf(::isValidSozuTag)
    val validAddress = address?.takeIf(::isValidStellarAddress)
    val target = SozuPayTarget(tag = validTag, address = validAddress)
    val canSend = cents > 0 && target.canPay() && !sending

    fun applyInput(raw: String) {
        val parsed = parseSozuPayInput(raw)
        if (parsed == null) {
            error = "Use a \$tag, a Stellar address, or a Sozu QR"
            return
        }
        parsed.tag?.let { tag = it }
        parsed.address?.let { address = it }
        error = null
        status = null
    }

    if (scanning) {
        QrOverlay(
            granted = camera.status.isGranted,
            onRequest = { camera.launchPermissionRequest() },
            onClose = { scanning = false },
            onScan = { raw ->
                scanning = false
                applyInput(raw)
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SOZU",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenChat) {
                Text("Chat", color = Color.White.copy(alpha = 0.45f))
            }
        }

        Text(
            text = formatCents(if (cents > 0) cents else 0),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
        )
        Text(
            text = "USDC",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$", color = Color.White, fontSize = 22.sp)
                BasicTextField(
                    value = tag,
                    onValueChange = { next ->
                        tag = normalizeSozuTag(next).take(30)
                        error = null
                        status = null
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (tag.isEmpty()) {
                                Text("tag", color = Color.White.copy(alpha = 0.25f), fontSize = 22.sp)
                            }
                            inner()
                        }
                    },
                )
            }
            if (validAddress != null) {
                Text(
                    text = shortAddress(validAddress),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val note = error ?: status
            if (note != null) {
                Text(
                    text = note,
                    color = if (error != null) Color(0xFFC4552A) else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                if (clip.isNullOrBlank()) {
                    error = "Clipboard is empty"
                } else {
                    applyInput(clip)
                }
            }) {
                Text("Paste", color = Color.White)
            }
            TextButton(onClick = { scanning = true }) {
                Text("QR", color = Color.White)
            }
            if (address != null) {
                TextButton(onClick = { address = null }) {
                    Text("Clear address", color = Color.White.copy(alpha = 0.45f))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(modifier = Modifier.fillMaxWidth()) {
            keys.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (key) {
                                "" -> Unit
                                "del" -> TextButton(onClick = {
                                    if (digits.isNotEmpty()) digits = digits.dropLast(1)
                                }) {
                                    Text("⌫", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
                                }
                                else -> TextButton(onClick = {
                                    if (digits.length < 8) digits += key
                                    status = null
                                }) {
                                    Text(key, color = Color.White, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                sending = true
                onSend(meshPayText(target, cents)) { accepted ->
                    sending = false
                    if (accepted) {
                        digits = ""
                        status = "Sent on the mesh"
                        error = null
                    } else {
                        error = "Not sent"
                    }
                }
            },
            enabled = canSend,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(
                    if (canSend) Color.White else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(28.dp),
                ),
        ) {
            Text(
                text = if (sending) "Sending…" else "Send",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QrOverlay(
    granted: Boolean,
    onRequest: () -> Unit,
    onClose: () -> Unit,
    onScan: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            SozuQrScanner(onScan = onScan)
        } else {
            TextButton(onClick = onRequest, modifier = Modifier.align(Alignment.Center)) {
                Text("Allow camera", color = Color.White)
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Text("Close", color = Color.White)
        }
    }
}
