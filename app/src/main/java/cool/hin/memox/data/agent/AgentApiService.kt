package cool.hin.memox.data.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cool.hin.memox.R
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences

/**
 * Keeps the configured Agent API alive only while the user enabled it and the app process is
 * allowed to run a data-sync foreground service. Stopping the service closes the socket and
 * deletes decrypted session caches.
 */
class AgentApiService : Service() {

    private var server: AgentApiServer? = null
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val api =
            AgentApiServer(applicationContext as android.content.ContextWrapper).also {
                it.ensureToken()
            }
        server = api
        if (!api.start()) {
            // Never leave the toggle on while nothing listens. save() posts through LiveData so
            // the settings screen (which observes this preference) updates even when the failed
            // rebind originated outside the fragment.
            MemoXPreferences.getInstance(applicationContext).agentApiEnabled.save(false)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notifications)
            .setContentTitle(getString(R.string.agent_api_running))
            .setContentText(getString(R.string.agent_api_running_detail))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_api_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "agent_api"
        private const val NOTIFICATION_ID = 47823
        const val ACTION_STOP = "cool.hin.memox.agent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentApiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentApiService::class.java))
        }
    }
}
