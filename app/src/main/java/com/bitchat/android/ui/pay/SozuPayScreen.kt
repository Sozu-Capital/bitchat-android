package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.ChatViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SozuPayScreen(
    viewModel: ChatViewModel,
    initialPeer: String? = null,
    onPayPeerOpened: () -> Unit = {},
    onOpenChat: () -> Unit,
    linkEpoch: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = rememberPermissionState(android.Manifest.permission.CAMERA)
    val prefs = remember { context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE) }
    val peers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val names by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val meshMessages by viewModel.messages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    var owner by remember { mutableStateOf(SozuWallet.loadOrCreate(context)) }
    var linked by remember {
        mutableStateOf(prefs.getString("linked", null)?.let(::isValidStellarAddress) == true)
    }
    var network by remember { mutableStateOf<List<SozuNetworkTx>>(emptyList()) }
    var myTag by remember { mutableStateOf(SozuTag.current(context).orEmpty()) }

    var digits by remember { mutableStateOf("") }
    var depositDigits by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var payee by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val book = remember { SozuLedger.load(context) }
    var left by remember { mutableStateOf(book.remaining()) }
    var ledgerTick by remember { mutableStateOf(0) }
    var balanceMinor by remember { mutableStateOf(prefs.getLong("balance-minor", -1L).takeIf { it >= 0L }) }

    val cents = centsFromDigits(digits)
    val ownerOk = owner.takeIf(::isValidStellarAddress)
    val payeeOk = payee.takeIf(::isValidStellarAddress)
    val cost = SozuCheque.usdcMinor(cents) + SozuCheque.DEMO_FEE
    val capped = book.collateral > 0L
    val canSend = selected != null && cents > 0 && ownerOk != null && payeeOk != null && !sending && (!capped || cost <= left)

    fun persistBook() {
        left = book.remaining()
        SozuLedger.save(context, book)
        ledgerTick += 1
    }

    fun showFlush(report: FlushReport) {
        for (upload in report.uploads) {
            val id = book.holdId(upload.frame) ?: continue
            if (upload.reason == "rejected") continue
            book.apply(id, upload.reason)
        }
        persistBook()
        when {
            report.hash != null -> {
                error = null
                status = "Submitted. ${report.hash.take(8)}"
                prefs.edit().remove("handed-frame").apply()
            }
            report.uploads.any { it.reason == "note-used" } -> {
                error = null
                status = "On testnet."
                prefs.edit().remove("handed-frame").apply()
            }
            report.uploads.any { it.reason == "collateral" } -> error = "Over your offline limit"
            report.uploads.any { it.reason == "passkey" } -> {
                val frame = report.uploads.first { it.reason == "passkey" }.frame
                status = "Tap Sign with passkey. This amount stays reserved."
                val open = synchronized(prefs) {
                    if (prefs.getString("handed-frame", null) == frame) {
                        false
                    } else {
                        prefs.edit().putString("handed-frame", frame).commit()
                        true
                    }
                }
                if (open) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://dev.sozu.capital/offline?frame=${Uri.encode(frame)}"),
                            ),
                        )
                    }
                }
            }
            report.uploads.any { it.reason == "rejected" } -> error = "Signed. The upload did not finish."
        }
        if (report.hash != null && isValidStellarAddress(owner)) {
            scope.launch {
                val minor = withContext(Dispatchers.IO) { SozuPending.fetchBalanceMinor(owner) } ?: return@launch
                prefs.edit().putLong("balance-minor", minor).apply()
                balanceMinor = minor
            }
        }
    }

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
        if (peer == null) {
            error = "Tap someone first"
            return
        }
        rememberPayee(peer, address)
        error = null
    }

    LaunchedEffect(Unit) {
        val activity = context as? Activity
        if (activity != null) {
            runCatching { owner = SozuWallet.ensure(context, activity) }
        }
        val tag = SozuTag.current(context) ?: return@LaunchedEffect
        myTag = tag
        if (tag.isNotBlank()) viewModel.setNickname(tag)
        if (!isValidStellarAddress(owner) || SozuTag.synced(context) == tag) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { SozuDirectory.publish(tag, owner) }
        if (saved) SozuTag.markSynced(context, tag)
    }

    LaunchedEffect(linkEpoch) {
        val raw = SozuWallet.pendingLink ?: return@LaunchedEffect
        SozuWallet.pendingLink = null
        val link = parseSozuLink(raw) ?: return@LaunchedEffect
        SozuWallet.useWebWallet(context, link.address)
        owner = link.address
        linked = true
        val linkedTag = link.tag
        if (linkedTag != null) {
            SozuTag.choose(context, linkedTag)
            myTag = linkedTag
            viewModel.setNickname(linkedTag)
        }
        if (link.minor > 0L) {
            book.collateral = link.minor
            persistBook()
            depositDigits = ""
            status = "${formatMinorUnits(link.minor)} is offline."
        } else {
            status = "Sozu wallet linked."
        }
        scope.launch {
            val minor = withContext(Dispatchers.IO) { SozuPending.fetchBalanceMinor(link.address) } ?: return@launch
            prefs.edit().putLong("balance-minor", minor).apply()
            balanceMinor = minor
        }
        error = null
    }

    LaunchedEffect(Unit) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                runCatching { SozuPending.fetchNetwork() }.getOrDefault(emptyList())
            }
            network = next
            kotlinx.coroutines.delay(15_000)
        }
    }

    LaunchedEffect(peers, owner, myTag) {
        if (!isValidStellarAddress(owner)) return@LaunchedEffect
        val line = if (myTag.isNotBlank()) "sozu-wallet:$owner\nsozu-tag:$myTag" else "sozu-wallet:$owner"
        while (true) {
            peers.forEach { peer ->
                val known = prefs.getString("payee:$peer", null)?.takeIf(::isValidStellarAddress)
                if (known != null) return@forEach
                viewModel.sendToPeer(peer, line) { }
            }
            kotlinx.coroutines.delay(4_000)
        }
    }

    LaunchedEffect(meshMessages, privateChats, selected) {
        val mine = viewModel.myPeerID
        fun learn(peerId: String?, content: String) {
            content.lineSequence().firstOrNull { it.startsWith("SOZU1:") }?.let { frame ->
                SozuPending.remember(context, frame)
                if (isValidStellarAddress(owner) && SozuInbox.remember(context, frame, owner)) {
                    ledgerTick += 1
                }
            }
            if (peerId.isNullOrBlank() || peerId == mine) return
            val address = SozuWallet.addressFromMeshText(content)
            val tag = SozuWallet.tagFromMeshText(content)
            if (tag != null) prefs.edit().putString("peerTag:$peerId", tag).apply()
            if (address != null) {
                prefs.edit().putString("payee:$peerId", address).apply()
                if (selected == peerId) payee = address
            }
        }
        meshMessages.forEach { learn(it.senderPeerID, it.content) }
        privateChats.forEach { (_, items) ->
            items.forEach { learn(it.senderPeerID, it.content) }
        }
    }

    LaunchedEffect(initialPeer) {
        val peer = initialPeer ?: return@LaunchedEffect
        selected = peer
        payee = prefs.getString("payee:$peer", null)?.takeIf(::isValidStellarAddress).orEmpty()
        digits = ""
        error = null
        status = null
        onPayPeerOpened()
    }

    LaunchedEffect(selected, payee, owner) {
        val peer = selected ?: return@LaunchedEffect
        if (payee.isNotBlank() || !isValidStellarAddress(owner)) return@LaunchedEffect
        val tag = prefs.getString("peerTag:$peer", null) ?: return@LaunchedEffect
        if (!SozuPending.online(context)) return@LaunchedEffect
        val address = withContext(Dispatchers.IO) { SozuPending.resolveTag(tag) } ?: return@LaunchedEffect
        prefs.edit().putString("payee:$peer", address).apply()
        if (selected == peer) payee = address
    }

    DisposableEffect(context, owner) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val report = withContext(Dispatchers.IO) { SozuPending.flush(context, owner) }
                    showFlush(report)
                }
            }

            override fun onLost(network: Network) {
                prefs.edit().remove("handed-frame").commit()
            }
        }
        var registered = false
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            }
        }
        onDispose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !isValidStellarAddress(owner)) return@LifecycleEventObserver
            scope.launch {
                val report = withContext(Dispatchers.IO) { SozuPending.flush(context, owner) }
                showFlush(report)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun openSettlement(frame: String) {
        if (!frame.startsWith("SOZU1:")) return
        prefs.edit().putString("handed-frame", frame).commit()
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://dev.sozu.capital/offline?frame=${Uri.encode(frame)}"),
                ),
            )
        }
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

    LaunchedEffect(owner) {
        if (!isValidStellarAddress(owner) || !SozuPending.online(context)) return@LaunchedEffect
        val minor = withContext(Dispatchers.IO) { SozuPending.fetchBalanceMinor(owner) } ?: return@LaunchedEffect
        prefs.edit().putLong("balance-minor", minor).apply()
        balanceMinor = minor
    }

    if (selected == null) {
        LedgerHome(
            owner = owner,
            linked = linked,
            tag = myTag,
            network = network,
            offlineMinor = remember(ledgerTick) { book.remaining() },
            walletMinor = balanceMinor,
            depositDigits = depositDigits,
            onDepositDigit = { key ->
                depositDigits = when (key) {
                    "del" -> depositDigits.dropLast(1)
                    "100" -> "100"
                    "500" -> "500"
                    "1000" -> "1000"
                    else -> if (key.length == 1 && depositDigits.length < 8) depositDigits + key else depositDigits
                }
                status = null
                error = null
            },
            onDeposit = {
                val minor = SozuCheque.usdcMinor(centsFromDigits(depositDigits))
                val wallet = balanceMinor
                when {
                    minor <= 0L -> error = "Enter an amount to deposit"
                    wallet != null && minor > wallet -> error = "That is more than your Sozu wallet"
                    else -> {
                        book.collateral = minor
                        persistBook()
                        depositDigits = ""
                        error = null
                        status = "Offline limit is ${formatMinorUnits(minor)}. Confirm this deposit from your Sozu wallet."
                        val url = "https://dev.sozu.capital/offline?deposit=${formatMinorUnits(minor)}"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                }
            },
            payments = remember(ledgerTick, owner) { SozuLedger.rows(context, book, owner) },
            onUpload = { row -> row.frame?.let(::openSettlement) },
            note = error ?: status,
            noteIsError = error != null,
            peers = peers,
            names = names,
            onOpenChat = onOpenChat,
            onPick = { peerId ->
                selected = peerId
                payee = prefs.getString("payee:$peerId", null)?.takeIf(::isValidStellarAddress).orEmpty()
                digits = ""
                error = null
                status = null
            },
        )
        return
    }

    val peerId = selected ?: return
    val label = names[peerId]?.takeIf { it.isNotBlank() }
        ?: prefs.getString("peerTag:$peerId", null)
        ?: "Nearby"
    Column(modifier = Modifier.sozuFrame()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            TextButton(onClick = {
                selected = null
                digits = ""
                error = null
                status = null
            }) { Text("Back", color = Color.White.copy(alpha = 0.7f)) }
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (myTag.isNotBlank()) "$$myTag" else shortAddress(owner),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        Text(
            text = formatCents(cents),
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = if (payeeOk != null) "To ${shortAddress(payeeOk)}" else "Waiting for their wallet",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (ownerOk != null) "You ${shortAddress(ownerOk)}" else "Your wallet is not ready",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clickable {
                    if (ownerOk != null) copyWallet(context, ownerOk)
                    status = "Your wallet copied"
                    error = null
                },
        )
        Text(
            text = balanceMinor?.let { "${formatMinorUnits(it)} USDC" } ?: "Balance unavailable",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val note = error ?: status
        if (note != null) {
            Text(
                text = note,
                color = if (error != null) Color(0xFFC4552A) else Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(modifier = Modifier.fillMaxWidth()) {
            keys.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier.weight(1f).height(48.dp),
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
                val activity = context as? Activity
                if (activity == null || ownerOk == null || payeeOk == null) {
                    error = if (payeeOk == null) "Waiting for their wallet" else "Wallet is not ready"
                    return@TextButton
                }
                sending = true
                error = null
                status = "Confirm with passkey"
                SozuSendHaptic.begin(context, scope)
                scope.launch {
                    var reserved: ByteArray? = null
                    try {
                        val signer = DeviceChequeSigner(activity)
                        val amount = SozuCheque.usdcMinor(cents)
                        val fee = SozuCheque.DEMO_FEE
                        Log.i(
                            "SozuPay",
                            "send owner=${StellarStrKey.diagnose(ownerOk)} payee=${StellarStrKey.diagnose(payeeOk)} amount=$amount fee=$fee left=${book.remaining()}",
                        )
                        val payeeId = StellarStrKey.accountId(payeeOk)
                            ?: throw IllegalArgumentException("Payee ${StellarStrKey.diagnose(payeeOk)}")
                        val ownerId = StellarStrKey.accountId(ownerOk)
                            ?: throw IllegalArgumentException("Owner ${StellarStrKey.diagnose(ownerOk)}")
                        val noteId = SozuCheque.randomNoteId()
                        if (book.collateral > 0L) {
                            when (book.reserve(noteId, amount, fee)) {
                                Reserve.Collateral -> throw IllegalArgumentException("Over your offline limit")
                                Reserve.NoteUsed -> throw IllegalArgumentException("Note already used")
                                Reserve.Amount -> throw IllegalArgumentException("Enter an amount")
                                Reserve.Ok -> persistBook()
                            }
                            reserved = noteId
                        } else {
                            Log.i("SozuPay", "collateral unset, offline cap not applied")
                        }
                        val body = SozuCheque.encodeBody(
                            vault = SozuCheque.vaultId(),
                            noteId = noteId,
                            amount = amount,
                            fee = fee,
                            cap = amount + fee,
                            expirySeconds = System.currentTimeMillis() / 1000L + 7L * 24L * 3600L,
                            payee = payeeId,
                            owner = ownerId,
                            deviceKey = signer.compressedPublicKey(),
                        )
                        val frame = SozuCheque.frame(body + signer.sign(body))
                        book.sent(noteId, amount, fee, frame)
                        persistBook()
                        viewModel.sendToPeer(peerId, frame) { accepted ->
                            sending = false
                            if (accepted) {
                                SozuSendHaptic.finish(context)
                                SozuPending.remember(context, frame)
                                digits = ""
                                selected = null
                                error = null
                                Log.i("SozuPay", "mesh accepted note=${noteKey(noteId)} left=${book.remaining()}")
                                if (SozuPending.online(context)) {
                                    status = "Uploading…"
                                    scope.launch {
                                        val report = withContext(Dispatchers.IO) { SozuPending.flush(context, owner) }
                                        showFlush(report)
                                    }
                                } else {
                                    status = "Saved on this phone. It uploads when this phone is online."
                                }
                            } else {
                                book.release(noteId)
                                persistBook()
                                SozuSendHaptic.stop(context)
                                error = "Not sent"
                                Log.w("SozuPay", "mesh rejected note=${noteKey(noteId)}")
                            }
                        }
                    } catch (e: Exception) {
                        reserved?.let {
                            book.release(it)
                            persistBook()
                        }
                        SozuSendHaptic.stop(context)
                        sending = false
                        error = e.message ?: "Could not sign"
                        Log.e("SozuPay", "pay failed ${e.message}")
                    }
                }
            },
            enabled = canSend,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
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
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LedgerHome(
    owner: String,
    linked: Boolean,
    tag: String,
    network: List<SozuNetworkTx>,
    offlineMinor: Long,
    walletMinor: Long?,
    depositDigits: String,
    onDepositDigit: (String) -> Unit,
    onDeposit: () -> Unit,
    payments: List<SozuLedgerRow>,
    onUpload: (SozuLedgerRow) -> Unit,
    note: String?,
    noteIsError: Boolean,
    peers: List<String>,
    names: Map<String, String>,
    onOpenChat: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.sozuFrame().verticalScroll(rememberScrollState())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text = "SOZU",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (tag.isNotBlank()) "$$tag" else "",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
            )
            TextButton(onClick = onOpenChat) { Text("Chat", color = Color.White.copy(alpha = 0.4f)) }
        }
        Text(
            text = formatMinorUnits(offlineMinor),
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = "Offline",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
        Text(
            text = when {
                owner.isBlank() -> "Open your Sozu wallet on dev.sozu.capital"
                !linked -> "Link this phone from dev.sozu.capital/offline"
                else -> "Wallet ${shortAddress(owner)}"
            },
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp).clickable {
                if (owner.isNotBlank()) copyWallet(context, owner)
            },
        )
        Text(
            text = walletMinor?.let { "${formatMinorUnits(it)} USDC in your Sozu wallet" } ?: "Sozu wallet balance unavailable",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = formatCents(centsFromDigits(depositDigits)),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "Deposit from your Sozu wallet into offline",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("100" to "1", "500" to "5", "1000" to "10", "del" to "⌫").forEach { (key, label) ->
                TextButton(onClick = { onDepositDigit(key) }, modifier = Modifier.weight(1f)) {
                    Text(label, color = Color.White, fontSize = 18.sp)
                }
            }
        }
        TextButton(
            onClick = onDeposit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(Color.White, RoundedCornerShape(28.dp)),
        ) {
            Text(
                text = "Deposit into offline",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        if (note != null) {
            Text(
                text = note,
                color = if (noteIsError) Color(0xFFC4552A) else Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = "Payments",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
        if (payments.isEmpty()) {
            Text("No payments yet", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        }
        payments.forEach { row ->
            val who = row.who.takeIf(::isValidStellarAddress)?.let(::shortAddress) ?: "Someone"
            val upload = !row.incoming && row.state == "pending" && row.frame?.startsWith("SOZU1:") == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .then(if (upload) Modifier.clickable { onUpload(row) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (row.incoming) "From $who" else "To $who",
                        color = Color.White,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = if (upload) "Tap to upload" else paymentState(row.state),
                        color = if (upload) Color.White else Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(formatMinorUnits(row.amount), color = Color.White, fontSize = 16.sp)
            }
        }
        Text(
            text = "On testnet",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
        if (network.isEmpty()) {
            Text("No uploaded payments yet", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        }
        network.take(12).forEach { tx ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://stellar.expert/explorer/testnet/tx/${tx.hash}"),
                                ),
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tx.label, color = Color.White, fontSize = 14.sp)
                    Text(tx.hash.take(8), color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
            }
        }
        Text(
            text = "Pay",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
        )
        if (peers.isEmpty()) {
            Text("Looking for people nearby", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            peers.forEach { peerId ->
                val label = names[peerId]?.takeIf { it.isNotBlank() } ?: peerId.take(4)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 18.dp).clickable { onPick(peerId) },
                ) {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label.take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(label.take(12), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

private fun paymentState(state: String): String = when (state) {
    "settled" -> "On testnet"
    "used" -> "On testnet"
    else -> "Pending"
}

private fun copyWallet(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Sozu wallet", address))
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
