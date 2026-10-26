package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.ChatViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SozuPayScreen(
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = rememberPermissionState(android.Manifest.permission.CAMERA)
    val prefs = remember { context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE) }
    val peers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val names by viewModel.peerNicknames.collectAsStateWithLifecycle()

    var digits by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var owner by remember { mutableStateOf(prefs.getString("owner", "") ?: "") }
    var payee by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val cents = centsFromDigits(digits)
    val ownerOk = owner.takeIf(::isValidStellarAddress)
    val payeeOk = payee.takeIf(::isValidStellarAddress)
    val canSend = selected != null && cents > 0 && ownerOk != null && payeeOk != null && !sending

    fun rememberPayee(peerId: String, address: String) {
        payee = address
        prefs.edit().putString("payee:$peerId", address).apply()
    }

    fun applyInput(raw: String) {
        val parsed = parseSozuPayInput(raw)
        val address = parsed?.address
        if (address == null) {
            error = "Paste a Stellar address or a Sozu QR"
            return
        }
        val peer = selected
        if (peer != null && payeeOk == null) {
            rememberPayee(peer, address)
        } else if (ownerOk == null) {
            owner = address
            prefs.edit().putString("owner", address).apply()
        } else if (peer != null) {
            rememberPayee(peer, address)
        } else {
            owner = address
            prefs.edit().putString("owner", address).apply()
        }
        error = null
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
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(
                text = "SOZU",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenChat) {
                Text("Chat", color = Color.White.copy(alpha = 0.4f))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (peers.isEmpty()) {
                Text(
                    text = "Looking for people nearby",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            peers.forEach { peerId ->
                val label = names[peerId]?.takeIf { it.isNotBlank() } ?: peerId.take(4)
                val on = peerId == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .clickable {
                            selected = peerId
                            payee = prefs.getString("payee:$peerId", "") ?: ""
                            error = null
                            status = null
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label.take(1).uppercase(),
                            color = if (on) Color.Black else Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = label.take(12),
                        color = Color.White.copy(alpha = if (on) 1f else 0.55f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Text(
            text = formatCents(cents),
            color = Color.White,
            fontSize = 52.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        )
        Text(
            text = selected?.let { names[it] ?: "Nearby" } ?: "Tap someone",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when {
                payeeOk != null -> shortAddress(payeeOk)
                selected != null -> "Paste their Stellar address"
                else -> ownerOk?.let { "You ${shortAddress(it)}" } ?: "Paste your Stellar address"
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        val note = error ?: status
        if (note != null) {
            Text(
                text = note,
                color = if (error != null) Color(0xFFC4552A) else Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text?.toString()
                if (clip.isNullOrBlank()) error = "Clipboard is empty" else applyInput(clip)
            }) { Text("Paste", color = Color.White) }
            TextButton(onClick = { scanning = true }) { Text("QR", color = Color.White) }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(modifier = Modifier.fillMaxWidth()) {
            keys.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (key) {
                                "" -> Unit
                                "del" -> TextButton(onClick = {
                                    if (digits.isNotEmpty()) digits = digits.dropLast(1)
                                }) { Text("⌫", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp) }
                                else -> TextButton(onClick = {
                                    if (digits.length < 8) digits += key
                                    status = null
                                }) { Text(key, color = Color.White, fontSize = 24.sp) }
                            }
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                val peer = selected ?: return@TextButton
                val activity = context as? Activity
                if (activity == null || ownerOk == null || payeeOk == null) {
                    error = "Paste both Stellar addresses"
                    return@TextButton
                }
                sending = true
                error = null
                status = "Confirm with passkey"
                scope.launch {
                    try {
                        val signer = DeviceChequeSigner(activity)
                        val amount = SozuCheque.usdcMinor(cents)
                        val payeeId = StellarStrKey.accountId(payeeOk)
                            ?: throw IllegalArgumentException("That Stellar address is not valid")
                        val ownerId = StellarStrKey.accountId(ownerOk)
                            ?: throw IllegalArgumentException("Your Stellar address is not valid")
                        val body = SozuCheque.encodeBody(
                            vault = SozuCheque.vaultId(),
                            noteId = SozuCheque.randomNoteId(),
                            amount = amount,
                            fee = SozuCheque.DEMO_FEE,
                            cap = amount + SozuCheque.DEMO_FEE,
                            expirySeconds = System.currentTimeMillis() / 1000L + 7L * 24L * 3600L,
                            payee = payeeId,
                            owner = ownerId,
                            deviceKey = signer.compressedPublicKey(),
                        )
                        val frame = SozuCheque.frame(body + signer.sign(body))
                        viewModel.sendToPeer(peer, frame) { accepted ->
                            sending = false
                            if (accepted) {
                                digits = ""
                                status = "Cheque signed. Waiting for a node."
                                error = null
                            } else {
                                error = "Not sent"
                            }
                        }
                    } catch (e: Exception) {
                        sending = false
                        error = e.message ?: "Could not sign"
                    }
                }
            },
            enabled = canSend,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
                .background(
                    if (canSend) Color.White else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(28.dp),
                ),
        ) {
            Text(
                text = if (sending) "Signing…" else "Send",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
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
