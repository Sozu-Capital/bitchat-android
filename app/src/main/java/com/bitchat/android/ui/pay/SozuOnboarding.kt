package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.DataManager

/** Clears the status bar and the Android navigation bar, then leaves a tap margin. */
fun Modifier.sozuFrame(): Modifier = this
    .fillMaxSize()
    .background(Color.Black)
    .statusBarsPadding()
    .navigationBarsPadding()
    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 36.dp)

/** One screen for every permission. Android still shows its own system dialogs on top. */
@Composable
fun SozuAccessScreen(busy: Boolean, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.sozuFrame(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("SOZU", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, letterSpacing = 2.sp)
            Text(
                text = "Allow nearby payments",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                text = "Sozu uses Bluetooth to find people around you, location because Android requires it for that scan, and notifications so a payment can finish in the background.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        TextButton(
            onClick = onContinue,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(28.dp)),
        ) {
            Text(
                text = if (busy) "Asking…" else "Continue",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/** Asked once, after permissions. Joining stores the choice on the phone. */
@Composable
fun SozuNodeScreen(onJoin: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.sozuFrame(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("SOZU", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, letterSpacing = 2.sp)
            Text(
                text = "Keep the network in sync",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                text = "Be a node. When this phone has a connection, it uploads cheques from the mesh. Uploaders earn respect for keeping the offline network in sync.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Column {
            TextButton(
                onClick = onJoin,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(28.dp)),
            ) {
                Text(
                    text = "Join and earn respect",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

object SozuNode {
    fun decided(context: Context): Boolean {
        return context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE).contains("uploader")
    }

    fun choose(context: Context, join: Boolean) {
        context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("uploader", join)
            .apply()
    }
}

object SozuTag {
    private fun prefs(context: Context) = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)

    fun decided(context: Context): Boolean = prefs(context).contains("tagChosen")

    fun current(context: Context): String? = prefs(context).getString("tag", null)?.takeIf(::isValidSozuTag)

    fun synced(context: Context): String? = prefs(context).getString("tagSynced", null)

    fun suggested(context: Context): String = sozuTagFromNickname(DataManager(context).loadNickname())

    fun choose(context: Context, tag: String) {
        prefs(context).edit().putString("tag", tag).putBoolean("tagChosen", true).apply()
    }

    fun markSynced(context: Context, tag: String) {
        prefs(context).edit().putString("tagSynced", tag).apply()
    }
}

/** Asked once. Skip keeps the name bitchat already generated for this phone. */
@Composable
fun SozuTagScreen(suggested: String, onSave: (String) -> Unit, onSkip: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.sozuFrame().imePadding(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("SOZU", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, letterSpacing = 2.sp)
            Text(
                text = "Choose a tag",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                text = "This is how someone pays you later. It is saved in the Sozu directory. Skip and this phone keeps $$suggested.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(30)
                    error = null
                },
                placeholder = { Text(suggested, color = Color.White.copy(alpha = 0.35f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                    cursorColor = Color.White,
                ),
            )
            if (error != null) {
                Text(
                    text = error ?: "",
                    color = Color(0xFFC4552A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Column {
            TextButton(
                onClick = {
                    val tag = normalizeSozuTag(draft)
                    if (!isValidSozuTag(tag)) {
                        error = "Enter a tag, or skip to keep $$suggested."
                        return@TextButton
                    }
                    onSave(tag)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(28.dp)),
            ) {
                Text(
                    text = "Save tag",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** Creates the device passkey and the Stellar account the first time the app opens. */
@Composable
fun SozuDeviceKey() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? Activity ?: return@LaunchedEffect
        runCatching { SozuWallet.ensure(context, activity) }
    }
}
