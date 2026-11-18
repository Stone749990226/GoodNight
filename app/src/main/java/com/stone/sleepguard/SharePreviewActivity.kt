package com.stone.sleepguard

import android.content.*
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SharePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val exportDirectory = File(cacheDir, "exports").canonicalFile
        val paths = intent.getStringArrayListExtra("paths").orEmpty().map { File(it).canonicalFile }.filter { it.parentFile == exportDirectory && it.isFile }
        setContent { NightTheme {
            Scaffold(containerColor = Ink, contentColor = Cream, bottomBar = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("返回") }
                    Button(enabled = paths.isNotEmpty(), onClick = { share(paths) }, modifier = Modifier.weight(2f).heightIn(min = 56.dp)) { Text("选择朋友并分享") }
                }
            }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Column(Modifier.padding(top = 20.dp)) { Text("分享预览", style = MaterialTheme.typography.headlineSmall); Text("${paths.size} 张图片 · 请先确认照片和原因内容", color = Muted) } }
                    items(paths, key = { it.absolutePath }) { file ->
                        var bitmap by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(file) {
                            bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                        }
                        bitmap?.let { image -> AndroidView(factory = { context -> ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER } }, update = { it.setImageBitmap(image) }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        } }
    }
    private fun share(files: List<File>) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = "image/png"; send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(contentResolver, "晚安作息记录", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        startActivity(Intent.createChooser(send, "分享给朋友"))
    }
}
