package com.stone.sleepguard

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailStatus {
    const val PENDING = 0
    const val SENDING = 1
    const val SENT = 2
    const val FAILED = 3
}

private object SendLock {
    val mutex = Mutex()
}

class EmailSender(private val context: Context) {
    private val app get() = context.applicationContext as SleepApp
    private val dao get() = app.repository.dao
    private val preferences get() = app.emailPreferences

    suspend fun sendPending() = withContext(Dispatchers.IO) {
        SendLock.mutex.withLock {
            val config = preferences.flow.first()
            if (!config.enabled || !config.configured) return@withLock
            dao.resetSendingEmails()
            dao.pendingEmails().forEach { job ->
                if (dao.claimEmail(job.id) == 0) return@forEach
                runCatching { send(config, job.subject, job.body) }
                    .onSuccess { dao.markEmailSent(job.id) }
                    .onFailure { error ->
                        val failed = job.attempts + 1 >= 8
                        dao.markEmailFailed(job.id, if (failed) EmailStatus.FAILED else EmailStatus.PENDING,
                            error.message ?: error.javaClass.simpleName)
                    }
            }
        }
    }

    suspend fun pendingCount(): Int = dao.pendingEmailCount()

    suspend fun sendTest() = withContext(Dispatchers.IO) {
        val config = preferences.flow.first()
        check(config.configured) { "请先填写发件邮箱、授权码和对方邮箱" }
        send(config, "🌙 晚安 · 邮件自测", "这是一封测试邮件。\n\n事件邮件会从 ${config.from} 发到 ${config.partner}。")
    }

    private fun send(config: EmailSettings, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", "true")
            if (config.port == 465) put("mail.smtp.ssl.enable", "true")
            else { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(config.from, config.authCode)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from, config.myName.takeIf { it.isNotBlank() }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.partner))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(message)
    }
}

class EmailWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = EmailPreferences(applicationContext).flow.first()
        if (!config.enabled || !config.configured) return Result.success()
        val sender = EmailSender(applicationContext)
        sender.sendPending()
        return if (sender.pendingCount() > 0) Result.retry() else Result.success()
    }
}

object EmailWork {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<EmailWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("email_outbox", ExistingWorkPolicy.KEEP, request)
    }
}
