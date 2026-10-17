package ru.focusguard.android

import android.app.ActivityManager
import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import ru.focusguard.core.*

/** Run only on disposable API31+ emulator with lab Device Owner provisioned by script. */
@RunWith(AndroidJUnit4::class)
class ProvisioningSmokeTest {
    @Test fun minimalLockTaskAndNoWipeDeprovision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        check(BuildConfig.LAB_DEVICE && BuildConfig.DEBUG)
        val controller = PolicyController(ctx)
        assertTrue("Provision DO before running", controller.isOwner)
        val store = Storage(ctx)
        // Each rerun is a new lab procedure, not a continuation of a completed one.
        synchronized(Storage.lock) {
            store.write(store.read().copy(removal = Removal.NONE, deprovisionStage = 0,
                activated = false, mode = Mode.LOCKED, flow = null, challenge = null, error = ""))
        }
        val sentinel = File(ctx.filesDir, "personal-data-sentinel.txt").apply { writeText("must survive deprovision") }
        val dpm = controller.dpm
        dpm.setLockTaskPackages(controller.admin, emptyArray())
        dpm.setLockTaskPackages(controller.admin, arrayOf(ctx.packageName))
        dpm.setLockTaskFeatures(controller.admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK)
        dpm.addPersistentPreferredActivity(controller.admin, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }, ComponentName(ctx, MainActivity::class.java))
        // A software-only emulator can exceed MonitoringInstrumentation's fixed 45s idle
        // timeout during cold Compose startup. Wait for the real Activity lifecycle instead.
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = requireNotNull(monitor.waitForActivityWithTimeout(120_000)) { "HOME activity did not start" }
        instrumentation.removeMonitor(monitor)
        instrumentation.runOnMainSync { controller.enter(activity) }
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, ctx.getSystemService(ActivityManager::class.java).lockTaskModeState)
        assertFalse(dpm.isLockTaskPermitted("com.android.settings"))
        val state = store.read().copy(removal = Removal.DEPROVISIONING)
        store.write(state)
        var completed: State? = null
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                completed = synchronized(Storage.lock) { controller.deprovision(store.read(), store::write) }
                break
            } catch (e: Exception) {
                lastError = e
                store.audit("smoke retry ${attempt + 1}: stage ${store.read().deprovisionStage}, ${e.javaClass.simpleName}")
                Thread.sleep(1000)
            }
        }
        val result = completed ?: throw requireNotNull(lastError)
        assertEquals(Removal.COMPLETE, result.removal)
        assertFalse(controller.isOwner); assertFalse(dpm.isAdminActive(controller.admin))
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, ctx.getSystemService(ActivityManager::class.java).lockTaskModeState)
        assertEquals("must survive deprovision", sentinel.readText())
        instrumentation.runOnMainSync { activity.finish() }
    }
}
