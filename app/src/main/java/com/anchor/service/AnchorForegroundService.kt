package com.anchor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anchor.R
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.OverrideStatus
import com.anchor.data.settings.SettingsRepository
import com.anchor.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process warm so the accessibility service is not reaped, and owns
 * the persistent notification that tells the user whether the remote override
 * is currently active (spec §4, "UI Indication").
 */
@AndroidEntryPoint
class AnchorForegroundService : Service() {

    @Inject lateinit var killSwitch: KillSwitch
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(OverrideStatus.INACTIVE))
        observeOverride()
    }

    /**
     * Polls the kill switch for the *notification text only*. Blocking
     * decisions never read this; they call KillSwitch themselves at the
     * moment of blocking, as the spec requires.
     */
    private fun observeOverride() = scope.launch {
        while (true) {
            val settings = settingsRepository.current()
            val status = if (settings.killSwitchEnabled) {
                killSwitch.check(settings)
            } else {
                OverrideStatus.INACTIVE
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status))
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private fun buildNotification(status: OverrideStatus): Notification {
        val text = when (status) {
            OverrideStatus.ACTIVE -> "Override active: blocking is disabled"
            OverrideStatus.INACTIVE -> "Protocol active"
            OverrideStatus.UNKNOWN -> "Protocol active (Home Assistant unreachable)"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Anchor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_anchor_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Anchor status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows whether the Anchor protocol is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "anchor_status"
        private const val NOTIFICATION_ID = 4200
        private const val POLL_INTERVAL_MILLIS = 60_000L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AnchorForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnchorForegroundService::class.java))
        }
    }
}
