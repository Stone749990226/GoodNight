package com.stone.sleepguard

import android.app.TimePickerDialog
import android.content.DialogInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun ClockTimeDialog(
    initial: Int,
    title: String,
    subtitle: String? = null,
    confirmLabel: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val context = LocalContext.current
    val confirm by rememberUpdatedState(onConfirm)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(context) {
        val time = Math.floorMod(initial, 24 * 60)
        val dialog = TimePickerDialog(
            context, R.style.GoodNightClockDialog,
            { _, hour, minute -> confirm(hour * 60 + minute) },
            time / 60, time % 60, true,
        ).apply {
            setTitle(subtitle?.let { "$title\n$it" } ?: title)
            setButton(DialogInterface.BUTTON_POSITIVE, confirmLabel, this)
            setButton(DialogInterface.BUTTON_NEGATIVE, "取消", this)
            setOnCancelListener { dismiss() }
        }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
