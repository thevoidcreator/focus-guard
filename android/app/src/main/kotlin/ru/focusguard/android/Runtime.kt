package ru.focusguard.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import ru.focusguard.core.*

class GuardRuntime(private val ctx: Context) {
    fun update(event: String = "tick", transition: (State) -> State = { it }): State = synchronized(Storage.lock) {
        val store = Storage(ctx)
        val reading = store.reading()
        var s = store.read()
        if (s.daily != null) {
            val d = s.daily!!
            // When monitoring is unavailable ordinary apps are locked. Do not charge
            // that locked interval as if the user were using an ordinary app.
            val charge = if (s.activated && UsageMonitor.ready(ctx)) runCatching { UsageMonitor.charging(ctx, s) }.getOrDefault(true) else false
            val next = DailyEngine.tick(d, reading, charge).let { if (d.boot != reading.boot) it.copy(unlimited = false) else it }
            val open = UsageMonitor.ready(ctx) && (next.remaining > 0 || next.unlimited && next.time(reading) != null)
            s = s.copy(daily = next, mode = if (open) Mode.SESSION_ACTIVE else Mode.LOCKED, sessionDeadline = 0)
        } else s = Engine.tick(s, reading)
        s = transition(s)
        if (s.daily != null) {
            val d = s.daily!!
            val open = UsageMonitor.ready(ctx) && (d.remaining > 0 || d.unlimited && d.time(reading) != null)
            s = s.copy(mode = if (open) Mode.SESSION_ACTIVE else Mode.LOCKED)
        }
        store.write(s) // Durable state before any relaxation of OS policy.
        if (event != "tick") store.audit(event)
        try {
            schedule(s)
            val controller = PolicyController(ctx)
            if (s.removal == Removal.DEPROVISIONING) s = controller.deprovision(s, store::write)
            else if (s.daily == null || event != "tick" || lastMode != s.mode || reading.mono - lastReconcile > 10_000) {
                controller.reconcile(s); lastMode = s.mode; lastReconcile = reading.mono
            }
            if (s.error.isNotEmpty()) { s = s.copy(error = ""); store.write(s) }
        } catch (e: Exception) {
            s = store.read()
            s = s.copy(error = "Этап ${s.deprovisionStage}: ${e.javaClass.simpleName}: ${e.message}")
            store.write(s); store.audit(s.error)
            // Do not leave an unsuccessful expansion reflected as an active session.
            if (s.mode == Mode.SESSION_ACTIVE) {
                s = Engine.endSession(s); store.write(s)
                runCatching { PolicyController(ctx).reconcile(s) }
            }
        }
        schedule(s)
        s
    }
    // USE_EXACT_ALARM is declared and canScheduleExactAlarms is checked before every deadline.
    @android.annotation.SuppressLint("MissingPermission")
    private fun schedule(s: State) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val checkpoint = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, TickReceiver::class.java).setAction("${ctx.packageName}.TICK"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val deadline = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, TickReceiver::class.java).setAction("${ctx.packageName}.TICK"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!s.activated || s.removal == Removal.COMPLETE) { alarm.cancel(checkpoint); alarm.cancel(deadline); return }
        // Checkpoints must not consume the allow-while-idle quota needed for the deadline.
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + MINUTE, checkpoint)
        if (s.daily == null && s.mode == Mode.SESSION_ACTIVE) {
            check(alarm.canScheduleExactAlarms()) { "ОС не разрешила точный таймер; сеанс закрыт" }
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, s.sessionDeadline, deadline)
        }
        else alarm.cancel(deadline)
    }
    companion object { private var lastMode: Mode? = null; private var lastReconcile = -1L }
}
class AdminReceiver : DeviceAdminReceiver()
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf("${context.packageName}.TICK", Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        Thread {
            try { runCatching {
                val s = GuardRuntime(context).update()
                if (s.daily != null && s.activated && s.removal != Removal.COMPLETE) context.startForegroundService(Intent(context, BudgetService::class.java))
            }.onFailure { runCatching { PolicyController(context).failClosed() }; Storage(context).audit("STATE_ERROR ${it.javaClass.simpleName}") } }
            finally { pending.finish() }
        }.start()
    }
}
class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED)) return
        val pending = goAsync()
        Thread { try { runCatching { GuardRuntime(context).update("package_change") } } finally { pending.finish() } }.start()
    }
}
