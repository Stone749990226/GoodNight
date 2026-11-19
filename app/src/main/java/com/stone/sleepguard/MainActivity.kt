package com.stone.sleepguard

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as SleepApp
    private val busy = MutableStateFlow(false)
    private val permissionTick = MutableStateFlow(0)
    private var pendingPhotoPath: String? = null
    private var pendingPhotoNight = 0L
    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPhotoPath; val id = pendingPhotoNight
        pendingPhotoPath = null; pendingPhotoNight = 0
        if (success && path != null && File(path).length() > 0) runAction { app.repository.photo(id, path) }
        else path?.let { File(it).delete() }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick.value++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPhotoPath = savedInstanceState?.getString("photoPath")
        pendingPhotoNight = savedInstanceState?.getLong("photoNight") ?: 0
        enableEdgeToEdge()
        setContent {
            NightTheme {
                val records by app.repository.records.collectAsStateWithLifecycle(emptyList())
                val preferences by app.preferences.flow.collectAsStateWithLifecycle(SleepSettings())
                val email by app.emailPreferences.flow.collectAsStateWithLifecycle(EmailSettings())
                val roleState by app.rolePreferences.flow.collectAsStateWithLifecycle(RoleState())
                val role = roleState.role
                val partner = partnerName(role)
                val working by busy.collectAsStateWithLifecycle()
                val tick by permissionTick.collectAsStateWithLifecycle()
                val message by app.message.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var feelingId by rememberSaveable { mutableStateOf<Long?>(null) }
                var morningPrompt by rememberSaveable { mutableStateOf(false) }
                var wakeDialog by rememberSaveable { mutableStateOf(false) }
                var diagnostic by remember { mutableStateOf(false) }
                var exportRange by remember { mutableStateOf(false) }
                var deleted by remember { mutableStateOf<NightRecord?>(null) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var power by remember { mutableStateOf(powerStatus(this)) }
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) { now = System.currentTimeMillis(); power = powerStatus(this@MainActivity); delay(1000) }
                    }
                }
                LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (app.message.value == it) app.message.value = null } }
                LaunchedEffect(deleted) {
                    val record = deleted ?: return@LaunchedEffect
                    val result = snackbar.showSnackbar("已删除 ${record.night.date} 晚的记录", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) runAction { app.repository.restore(record) }
                    else record.night.photo?.let { path -> File(path).delete() }
                    deleted = null
                }
                LaunchedEffect(records, tick) {
                    if (intent.getBooleanExtra("wakePrompt", false)) {
                        morningPrompt = true
                        intent.removeExtra("wakePrompt")
                        MonitorService.dismissMorningPrompt()
                    }
                    if (intent.getBooleanExtra("feeling", false) && records.isNotEmpty()) {
                        feelingId = records.firstOrNull { it.night.phase == Phase.AWAKE && it.night.feeling == null }?.night?.id
                        intent.removeExtra("feeling")
                    } else if (feelingId == null) {
                        val recent = records.firstOrNull {
                            it.night.phase == Phase.AWAKE && it.night.feeling == null &&
                                (it.night.wakeAt ?: 0) > System.currentTimeMillis() - 6 * 3_600_000L
                        }
                        if (recent != null) feelingId = recent.night.id
                    }
                }
                val wakeNow: () -> Unit = {
                    runAction { val id = app.repository.wake(); MonitorService.releaseNow(); stopService(Intent(this@MainActivity, MonitorService::class.java)); feelingId = id }
                }
                val active = records.firstOrNull { it.night.phase != Phase.AWAKE }
                val lastAwake = records.firstOrNull { it.night.phase == Phase.AWAKE }
                Scaffold(containerColor = Ink, contentColor = Cream, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                    Row(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        listOf("今晚", "记录", "设置").forEachIndexed { i, label ->
                            Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable { tab = i }.padding(horizontal = 28.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                NavMark(i, tab == i); Spacer(Modifier.height(7.dp))
                                Text(label, color = if (tab == i) Mint else Muted, fontSize = 12.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }) { padding ->
                    key(tab) {
                        val pageScroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            when (tab) {
                                0 -> TonightScreen(active, lastAwake, now, power, preferences, working,
                                    partner = partner,
                                    onSleep = {
                                        if (active == null) wakeDialog = true
                                        else runAction { MonitorService.start(this@MainActivity, if (active.night.phase == Phase.RECOVERY) MonitorService.RESUME else MonitorService.SLEEP) }
                                    },
                                    onWake = wakeNow,
                                    onReason = { id, text -> runAction { app.repository.reason(id, text) } },
                                    onFeeling = { feelingId = it }, onPhoto = { photo(it) },
                                    onShare = { id -> share(records.filter { it.night.id == id }) }, onPermissions = { tab = 2 }, permissionTick = tick, context = this@MainActivity)
                                1 -> RecordsScreen(records, { feelingId = it }, { photo(it) }, { share(it) }, { id, text -> runAction { app.repository.reason(id, text) } },
                                    onEdit = { id, edit -> runAction { app.repository.edit(id, edit) } },
                                    onDelete = { id -> runAction { app.repository.deleteNight(id)?.let { deleted = it } } },
                                    scrollState = pageScroll)
                                else -> SettingsScreen(preferences, email, role, tick, this@MainActivity,
                                    onSave = { value -> runAction { app.preferences.save(value) } },
                                    onSaveEmail = { value -> runAction { app.emailPreferences.save(value) } },
                                    onTestEmail = { runAction { app.emailSender.sendTest(); app.message.value = "测试邮件已发送" } },
                                    onNotifications = { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) },
                                    onOverlay = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                                    onBattery = { openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
                                    onVivo = { openVivoSettings() }, onDiagnostic = { diagnostic = true }, onExport = { exportRange = true })
                            }
                            if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                        }
                    }
                }
                feelingId?.let { id -> Dialog(onDismissRequest = { }) {
                    Surface(shape = RoundedCornerShape(26.dp), color = Panel) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("早安，昨晚睡得怎么样？", fontSize = 20.sp, color = Cream, fontWeight = FontWeight.Medium)
                            Text("选一个真实感受才能结束这一晚；户外照片可稍后补拍，不是必填。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            listOf("清醒", "一般", "很困").forEach { feeling ->
                                OutlinedButton(onClick = { runAction { app.repository.feeling(id, feeling); feelingId = null } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(feeling, fontSize = 16.sp, color = Cream) }
                            }
                        }
                    }
                } }
                if (morningPrompt) AlertDialog(onDismissRequest = { morningPrompt = false }, title = { Text("醒了吗？") },
                    text = { Text("昨晚的守候还没结束。点「我醒了」结束后，可以补今天的感受。", color = Muted) },
                    confirmButton = { TextButton(onClick = { morningPrompt = false; wakeNow() }) { Text("我醒了") } },
                    dismissButton = { TextButton(onClick = { morningPrompt = false }) { Text("稍后") } },
                    containerColor = Panel, titleContentColor = Cream, textContentColor = Cream)
                if (wakeDialog) NightWakeDialog(preferences.targetWake, onDismiss = { wakeDialog = false },
                    onConfirm = { wake -> wakeDialog = false; runAction { MonitorService.start(this@MainActivity, MonitorService.SLEEP, wake) } })
                if (diagnostic) DiagnosticDialog(this@MainActivity) { diagnostic = false }
                if (exportRange) AlertDialog(onDismissRequest = { exportRange = false }, title = { Text("导出记录表格") }, text = {
                    Column { listOf(1 to "最近一晚", 7 to "最近7天", 30 to "最近30天").forEach { (days, label) -> TextButton(onClick = { exportRange = false; exportCsv(recordsForDays(records, days)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) } } }
                }, confirmButton = { TextButton(onClick = { exportRange = false }) { Text("取消") } })
                if (!roleState.loaded) Box(Modifier.fillMaxSize().background(Ink))
                else if (role == null) RolePickerScreen { chosen -> app.scope.launch { app.rolePreferences.choose(chosen) } }
            }
        }
    }
    override fun onResume() {
        super.onResume(); visible = true; permissionTick.value++
        MonitorService.dismissMorningPrompt()
        app.scope.launch { if (!MonitorService.running) app.repository.markRecovery() }
    }
    override fun onPause() { visible = false; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); permissionTick.value++ }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("photoPath", pendingPhotoPath); outState.putLong("photoNight", pendingPhotoNight); super.onSaveInstanceState(outState)
    }
    private fun runAction(block: suspend () -> Unit) {
        if (busy.value) return
        app.scope.launch { busy.value = true; try { block() } catch (e: Exception) { app.message.value = e.message ?: "操作失败，请再试一次" } finally { busy.value = false } }
    }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { app.message.value = "请在系统设置的应用管理中打开晚安" } }
    private fun openVivoSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        for (candidate in candidates) { try { startActivity(candidate); return } catch (_: Exception) { } }
        app.message.value = "请在设置 → 应用 → 晚安中允许后台运行和自启动"
    }
    private fun photo(id: Long) {
        val file = File(filesDir, "photos/${id}_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        pendingPhotoPath = file.absolutePath; pendingPhotoNight = id
        try { photoLauncher.launch(FileProvider.getUriForFile(this, "$packageName.files", file)) }
        catch (e: Exception) { pendingPhotoPath = null; app.message.value = "无法打开系统相机：${e.message}" }
    }
    private fun share(records: List<NightRecord>) = runAction {
        check(records.isNotEmpty()) { "还没有可分享的记录" }
        val files = withContext(Dispatchers.IO) { ShareExporter(this@MainActivity).images(records) }
        startActivity(Intent(this@MainActivity, SharePreviewActivity::class.java).putStringArrayListExtra("paths", ArrayList(files.map { it.absolutePath })))
    }
    private fun exportCsv(records: List<NightRecord>) = runAction {
        check(records.isNotEmpty()) { "还没有可导出的记录" }
        val file = withContext(Dispatchers.IO) { ShareExporter(this@MainActivity).csv(records) }
        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出作息表格"))
    }
    companion object { @Volatile var visible = false }
}

@Composable private fun RolePickerScreen(onPick: (Role) -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("晚安", fontSize = 30.sp, color = Cream, fontWeight = FontWeight.Light)
            Text("这台手机是谁的？", fontSize = 21.sp, color = Cream, fontWeight = FontWeight.Medium)
            Text("选好后不能更改，我会一直记得你。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { onPick(Role.STONE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                Text("我是 Stone 💙", fontSize = 17.sp, color = Cream)
            }
            OutlinedButton(onClick = { onPick(Role.FANNY) }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                Text("我是 Fanny 💗", fontSize = 17.sp, color = Cream)
            }
        }
    }
}

@Composable private fun NightWakeDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    WheelTimeDialog(initial = initial, title = "今晚，几点醒？", subtitle = "给自己一个起床约定",
        confirmLabel = "开始晚安", onDismiss = onDismiss, onConfirm = onConfirm)
}
