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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One screen for every permission. Android still shows its own system dialogs on top. */
@Composable
fun SozuAccessScreen(busy: Boolean, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 28.dp),
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 28.dp),
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

/** Creates the device passkey and the Stellar account the first time the app opens. */
@Composable
fun SozuDeviceKey() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? Activity ?: return@LaunchedEffect
        runCatching {
            SozuWallet.loadOrCreate(context)
            DeviceChequeSigner(activity).prepare()
        }
    }
}
