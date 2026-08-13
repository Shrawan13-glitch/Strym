package com.strym.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.strym.app.logging.CoreLogBuffer
import com.strym.app.logging.SecretRedactor
import com.strym.app.logging.StrymLogSink
import com.strym.app.service.StreamService
import uniffi.stream_ffi.LogLevel
import uniffi.stream_ffi.setMaxLogLevel
import uniffi.stream_ffi.setLogSink

class StrymApp : Application() {

    /** App-wide redactor + buffer: secrets registered here are masked in every
     * core log line and the buffer feeds "report an issue" exports. */
    val logRedactor = SecretRedactor()
    val logBuffer = CoreLogBuffer()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        installCoreLogging()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            StreamService.CHANNEL_STREAMING,
            getString(R.string.notification_channel_streaming),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun installCoreLogging() {
        setMaxLogLevel(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO)
        setLogSink(StrymLogSink(logRedactor, logBuffer))
    }
}
