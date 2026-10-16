package app.minemaps.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.minemaps.BuildConfig
import app.minemaps.R

class MineMapsCarAppService : CarAppService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun createHostValidator(): HostValidator = hostValidator(applicationContext, BuildConfig.DEBUG)

    override fun onCreateSession(): Session = MapSession(
        onActiveChanged = { active ->
            if (active) startCarForeground() else stopCarForeground()
        },
    )

    override fun onDestroy() {
        stopCarForeground()
        super.onDestroy()
    }

    private fun startCarForeground() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minemaps:car_session")?.apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
        }
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Android Auto", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Running in Android Auto")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
    }

    private fun stopCarForeground() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val CHANNEL_ID = "minemaps_car"
        private const val NOTIFICATION_ID = 4102

        /**
         * Debug builds accept any host (DHU); release builds allow Google's Android Auto (`com.google.android.projection.gearhead`)
         * and AAOS template host certificates, including the newer Android Auto 14+ release signing key (`1ca8dcc0...`) that is
         * missing from `androidx.car.app`'s bundled `hosts_allowlist_sample`.
         */
        fun hostValidator(context: Context, debug: Boolean): HostValidator =
            if (debug) {
                HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            } else {
                HostValidator.Builder(context)
                    .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                    .addAllowedHost("com.google.android.projection.gearhead", "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295")
                    .addAllowedHost("com.google.android.projection.gearhead", "ee39411b93c116f8e0a15f7c2c13f88f31f0e2519b47142b1c1f40254b8b57d7")
                    .build()
            }
    }
}
