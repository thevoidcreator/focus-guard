package ru.focusguard.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** Only system document handlers, only for a bounded import/export transaction. */
object DocumentAccess {
    private var until = 0L
    private var packages = emptySet<String>()
    @Synchronized fun active(): Set<String> = if (SystemClock.elapsedRealtime() < until) packages else emptySet()
    fun open(ctx: Context) {
        val handlers = listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_CREATE_DOCUMENT).flatMap { action ->
            ctx.packageManager.queryIntentActivities(Intent(action).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
                .map { it.activityInfo.packageName }
        }.toSet()
        synchronized(this) { packages = handlers; until = SystemClock.elapsedRealtime() + 120_000 }
        GuardRuntime(ctx).update("document_open")
    }
    fun close(ctx: Context) {
        synchronized(this) { until = 0; packages = emptySet() }
        GuardRuntime(ctx).update("document_close")
    }
}
