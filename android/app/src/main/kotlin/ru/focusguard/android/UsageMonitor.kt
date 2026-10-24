package ru.focusguard.android

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.*
import android.media.session.*
import android.service.notification.NotificationListenerService
import android.provider.Settings
import ru.focusguard.core.*

class PlaybackAccess : NotificationListenerService() {
    override fun onListenerConnected() { UsageMonitor.connected = true }
    override fun onListenerDisconnected() { UsageMonitor.connected = false }
}
object UsageMonitor {
    private var cursor = 0L
    private val visible = mutableMapOf<String, String>()
    private var allowedCache = emptySet<String>()
    private var cacheUntil = 0L
    private var policyVersion = -1L
    @Volatile var connected = false
    fun usageAllowed(ctx: Context): Boolean = ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    fun notificationAllowed(ctx: Context): Boolean = ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(ctx, PlaybackAccess::class.java))
    fun ready(ctx: Context) = usageAllowed(ctx) && notificationAllowed(ctx) && connected
    fun charging(ctx: Context, s: State): Boolean {
        require(ready(ctx)) { "Нужны доступ к использованию и подключённый доступ к воспроизведению" }
        if (SystemClock.elapsedRealtime() >= cacheUntil || policyVersion != s.policyVersion) {
            val inventory = Inventory(ctx)
            allowedCache = inventory.dependencies() + inventory.verified(s.policy.apps)
            cacheUntil = SystemClock.elapsedRealtime() + 10_000
            policyVersion = s.policyVersion
        }
        val allowed = allowedCache
        val sessions = ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, PlaybackAccess::class.java))
        val playing = sessions.filter { it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING) }.map { it.packageName }.toSet()
        // Some players publish no MediaSession. Never silently make that playback free.
        // Attribution is unavailable to a normal DPC; unknown music is charged conservatively.
        val unknown = playing.isEmpty() && ctx.getSystemService(android.media.AudioManager::class.java).isMusicActive
        val screen = ctx.getSystemService(PowerManager::class.java).isInteractive && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val now = System.currentTimeMillis()
        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(if (cursor in 1..now) cursor else now - 24 * 60 * 60 * 1000L, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when(e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> visible["${e.packageName}/${e.className}"] = e.packageName
                UsageEvents.Event.ACTIVITY_STOPPED -> visible.remove("${e.packageName}/${e.className}")
            }
        }
        cursor = now
        return DailyEngine.charge(visible.values.toSet(), playing, allowed, screen, unknown)
    }
}
class BudgetService : Service() {
    private val thread = HandlerThread("daily-budget")
    private lateinit var handler: Handler
    private val tick = object : Runnable {
        override fun run() {
            val state = runCatching { GuardRuntime(this@BudgetService).update() }.onFailure { runCatching { PolicyController(this@BudgetService).failClosed() } }.getOrNull()
            if (state?.removal == Removal.COMPLETE || state?.activated == false) { stopSelf(); return }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("budget", "Учёт дневного лимита", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, Notification.Builder(this, "budget").setSmallIcon(ru.focusguard.android.R.drawable.guard_icon).setContentTitle("Focus Guard").setContentText("Дневной лимит включён").setContentIntent(open).setOngoing(true).build())
        thread.start(); handler = Handler(thread.looper); handler.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); thread.quitSafely(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
