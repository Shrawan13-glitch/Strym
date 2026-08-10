package com.strym.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.strym.app.service.StreamService
import uniffi.stream_ffi.LogLevel
import uniffi.stream_ffi.LogSink
import uniffi.stream_ffi.setMaxLogLevel
import uniffi.stream_ffi.setLogSink

class StrymApp : Application() {

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
        setLogSink(object : LogSink {
            override fun onLog(level: LogLevel, module: String, message: String) {
                Log.println(level.toPriority(), "strym-core", "$module: $message")
            }
        })
    }
}

private fun LogLevel.toPriority(): Int = when (this) {
    LogLevel.ERROR -> Log.ERROR
    LogLevel.WARN -> Log.WARN
    LogLevel.INFO -> Log.INFO
    LogLevel.DEBUG -> Log.DEBUG
    LogLevel.TRACE -> Log.VERBOSE
}
