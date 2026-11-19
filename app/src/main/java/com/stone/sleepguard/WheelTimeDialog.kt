package com.stone.sleepguard

import android.graphics.Color
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** 水表式滚动时间选择：两个 24 小时滚轮。 */
@Composable
internal fun WheelTimeDialog(initial: Int, title: String, subtitle: String? = null, confirmLabel: String = "保存",
    onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var hour by remember(initial) { mutableIntStateOf(initial / 60) }
    var minute by remember(initial) { mutableIntStateOf(initial % 60) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            subtitle?.let { Text(it, color = Muted, fontSize = 12.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                WheelColumn(hour, 0..23) { hour = it }
                Text("时", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
                WheelColumn(minute, 0..59) { minute = it }
                Text("分", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
}

@Composable
private fun WheelColumn(selected: Int, range: IntRange, onChange: (Int) -> Unit) {
    AndroidView(factory = { context ->
        NumberPicker(context).apply {
            minValue = range.first
            maxValue = range.last
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setFormatter(NumberPicker.Formatter { "%02d".format(it) })
            setTextColor(Color.rgb(232, 239, 233))
            setBackgroundColor(Color.TRANSPARENT)
            setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
            value = selected
        }
    }, update = { picker -> if (picker.value != selected) picker.value = selected }, modifier = Modifier.width(84.dp).height(168.dp))
}
