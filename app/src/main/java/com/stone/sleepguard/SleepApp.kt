package com.stone.sleepguard

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class SleepApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { SleepRepository(this) }
    val preferences by lazy { SleepPreferences(this) }
    val rolePreferences by lazy { RolePreferences(this) }
    val emailPreferences by lazy { EmailPreferences(this) }
    val emailSender by lazy { EmailSender(this) }
    val message = MutableStateFlow<String?>(null)
}
