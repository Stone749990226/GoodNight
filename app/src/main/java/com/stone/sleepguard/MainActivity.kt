package com.stone.sleepguard

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate

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
                var planDate by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
                var feelingId by rememberSaveable { mutableStateOf<Long?>(null) }
                var morningPrompt by rememberSaveable { mutableStateOf(false) }
                var wakeDialog by rememberSaveable { mutableStateOf(false) }
                var diagnostic by remember { mutableStateOf(false) }
                var deleted by remember { mutableStateOf<NightRecord?>(null) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var power by remember { mutableStateOf(powerStatus(this)) }
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) { now = System.currentTimeMillis(); power = powerStatus(this@MainActivity); delay(1000) }
                    }
                }
                LaunchedEffect(tick) {
                    if (intent.getBooleanExtra("plan", false)) { tab = 1; intent.removeExtra("plan") }
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
                    runAction {
                        val id = app.repository.wake()
                        MonitorService.releaseNow(); stopService(Intent(this@MainActivity, MonitorService::class.java))
                        if (id == SleepRepository.SHORT_NIGHT) toast("不足1小时，本次不记录") else feelingId = id
                    }
                }
                val active = records.firstOrNull { it.night.phase != Phase.AWAKE }
                val lastAwake = records.firstOrNull { it.night.phase == Phase.AWAKE }
                val today = LocalDate.now()
                val selectedPlanDate = LocalDate.ofEpochDay(planDate)
                val planRepo = app.planRepository
                val planTasks by remember(selectedPlanDate) { planRepo.tasks(selectedPlanDate) }.collectAsStateWithLifecycle(emptyList())
                val planHabits by planRepo.habits().collectAsStateWithLifecycle(emptyList())
                val planReminders by planRepo.reminders().collectAsStateWithLifecycle(emptyList())
                val planCheckIns by remember(selectedPlanDate) { planRepo.days(selectedPlanDate) }.collectAsStateWithLifecycle(emptyList())
                Scaffold(containerColor = Ink, contentColor = Cream, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                    Row(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("今晚", "计划", "记录", "设置").forEachIndexed { i, label ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { tab = i }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                NavMark(i, tab == i); Spacer(Modifier.height(7.dp))
                                Text(label, color = if (tab == i) Mint else Muted, fontSize = 12.sp,
                                    maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal)
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
                                    onShare = { id -> share(records.filter { it.night.id == id }) }, onPermissions = { tab = 3 }, permissionTick = tick, context = this@MainActivity)
                                1 -> PlanScreen(today, selectedPlanDate, planTasks, planHabits, planReminders, planCheckIns, pageScroll,
                                    onSelectDate = { planDate = it.toEpochDay() },
                                    onAddTask = { title -> runAction { planRepo.addTask(title, selectedPlanDate, null, null) } },
                                    onToggleTask = { id -> runAction { planRepo.toggleTask(id, selectedPlanDate) } },
                                    onEditTask = { id, title -> runAction { planRepo.renameTask(id, selectedPlanDate, title) } },
                                    onDeleteTask = { id -> runAction { planRepo.deleteTask(id) } },
                                    onSaveHabit = { habit, times -> runAction { planRepo.saveHabit(habit, times); PlanScheduler.rebuild(this@MainActivity) } },
                                    onDeleteHabit = { id -> runAction { planRepo.deleteHabit(id); PlanScheduler.rebuild(this@MainActivity) } },
                                    onHabitStep = { id, date -> runAction { planRepo.toggleHabit(id, date) } })
                                2 -> RecordsScreen(records, { feelingId = it }, { photo(it) }, { share(it) }, { id, text -> runAction { app.repository.reason(id, text) } },
                                    onEdit = { id, edit -> runAction { app.repository.edit(id, edit) } },
                                    onDelete = { id -> runAction { app.repository.deleteNight(id)?.let { deleted = it } } },
                                    scrollState = pageScroll)
                                else -> SettingsScreen(preferences, email, role, tick, this@MainActivity,
                                    onSave = { value -> runAction { app.preferences.save(value) } },
                                    onSaveEmail = { value -> runAction { app.emailPreferences.save(value) } },
                                    onTestEmail = { runAction { app.emailSender.sendTest() } },
                                    onNotifications = { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) },
                                    onOverlay = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                                    onBattery = { openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
                                    onBackground = { openBackgroundSettings() }, onDiagnostic = { diagnostic = true },
                                    onAlarm = { if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) })
                            }
                            if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                        }
                    }
                }
                feelingId?.let { id -> Dialog(onDismissRequest = { }) {
                    Surface(shape = RoundedCornerShape(26.dp), color = Panel) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("bb，昨晚睡得怎么样？🥺", fontSize = 20.sp, color = Cream, fontWeight = FontWeight.Medium)
                            FEELINGS.forEach { feeling ->
                                OutlinedButton(onClick = { runAction { app.repository.feeling(id, feeling.value); feelingId = null } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(feeling.label, fontSize = 16.sp, color = Cream) }
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
                if (!roleState.loaded) Box(Modifier.fillMaxSize().background(Ink))
                else if (role == null) RolePickerScreen { chosen -> app.scope.launch { app.rolePreferences.choose(chosen) } }
            }
        }
    }
    override fun onResume() {
        super.onResume(); visible = true; permissionTick.value++
        MonitorService.dismissMorningPrompt()
        app.scope.launch { if (!MonitorService.running) app.repository.markRecovery(); PlanScheduler.rebuild(this@MainActivity) }
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
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    private fun openBackgroundSettings() {
        val packagePage = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerKeeperActivity")),
                packagePage)
            "vivo", "iqoo" -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                packagePage)
            "oppo", "realme", "oneplus" -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
                packagePage)
            "huawei", "honor" -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                packagePage)
            else -> listOf(packagePage)
        }
        for (candidate in candidates) { if (runCatching { startActivity(candidate) }.isSuccess) return }
        app.message.value = "请在设置 → 应用 → 晚安中允许后台运行、自启动，并把省电策略设为无限制"
    }
    private fun photo(id: Long) {
        val file = File(filesDir, "photos/${id}_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        pendingPhotoPath = file.absolutePath; pendingPhotoNight = id
        try { photoLauncher.launch(FileProvider.getUriForFile(this, "$packageName.files", file)) }
        catch (e: Exception) { pendingPhotoPath = null; app.message.value = "无法打开系统相机：${e.message}" }
    }
    private fun share(records: List<NightRecord>) = runAction {
        check(records.isNotEmpty()) { "还没有可分享的记录" }
        val role = app.rolePreferences.flow.first().role
        val files = withContext(Dispatchers.IO) { ShareExporter(this@MainActivity, partnerName(role)).images(records) }
        startActivity(Intent(this@MainActivity, SharePreviewActivity::class.java).putStringArrayListExtra("paths", ArrayList(files.map { it.absolutePath })))
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
