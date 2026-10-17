package ru.focusguard.android

import android.app.Activity
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.focusguard.core.*

@Serializable data class PolicyJournal(
    val restrictions: Map<String, Boolean>, val lockPackages: List<String>, val lockFeatures: Int,
    val autoTime: Boolean, val autoZone: Boolean, val uninstallBlocked: Boolean,
    val suspendedByUs: Set<String> = emptySet()
)
class PolicyController(private val ctx: Context) {
    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    val admin = ComponentName(ctx, AdminReceiver::class.java)
    val isOwner get() = dpm.isDeviceOwnerApp(ctx.packageName)
    fun failClosed() { if (isOwner && BuildConfig.LAB_DEVICE) reconcile(State("corrupt-store", activated = true)) }
    private val journalFile = AtomicFile(File(ctx.createDeviceProtectedStorageContext().filesDir, "policy-journal.json"))
    private val restrictions = listOf(UserManager.DISALLOW_DEBUGGING_FEATURES, UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_ADD_MANAGED_PROFILE, UserManager.DISALLOW_MODIFY_ACCOUNTS)
    private fun readJournal(): PolicyJournal = Protocol.json.decodeFromString(journalFile.openRead().bufferedReader().use { it.readText() })
    private fun save(j: PolicyJournal) {
        val out = journalFile.startWrite()
        try { out.write(Protocol.json.encodeToString(j).toByteArray()); journalFile.finishWrite(out) }
        catch (e: Exception) { journalFile.failWrite(out); throw e }
    }
    private fun snapshot() {
        if (journalFile.baseFile.exists()) return
        val old = dpm.getUserRestrictions(admin)
        save(PolicyJournal(restrictions.associateWith { old.getBoolean(it) }, dpm.getLockTaskPackages(admin).toList(),
            dpm.getLockTaskFeatures(admin), dpm.getAutoTimeEnabled(admin), dpm.getAutoTimeZoneEnabled(admin), dpm.isUninstallBlocked(admin, ctx.packageName)))
    }
    fun reconcile(s: State) {
        if (!s.activated || s.removal in listOf(Removal.DEPROVISIONING, Removal.COMPLETE)) return
        require(isOwner) { "Device Owner отсутствует" }
        check(BuildConfig.LAB_DEVICE) { "Production заблокирован: нет испытанного адаптера выхода для Nothing Phone (3a)" }
        snapshot()
        restrictions.forEach { dpm.addUserRestriction(admin, it) }
        dpm.setAutoTimeEnabled(admin, true); dpm.setAutoTimeZoneEnabled(admin, true)
        dpm.setUninstallBlocked(admin, ctx.packageName, true)
        val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))
        val inv = Inventory(ctx)
        val installed = inv.all()
        val installedIds = installed.map { it.pkg }.toSet()
        val forbidden = inv.settingsPackages()
        val ordinary = if (s.mode == Mode.SESSION_ACTIVE) installed.filter { !it.system }.map { it.pkg }.toSet() + inv.launchable() else emptySet()
        val allowed = (inv.dependencies(installed) + inv.verified(s.policy.apps) + ordinary) - forbidden
        require(ctx.packageName in allowed)
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK)
        dpm.setLockTaskPackages(admin, allowed.toTypedArray())
        val journal = readJournal()
        val suspend = installed.filter { !it.system && it.pkg !in allowed && it.pkg != ctx.packageName }.map { it.pkg }.toSet()
        val toSuspend = suspend.filter { it in journal.suspendedByUs || !ctx.packageManager.isPackageSuspended(it) }.toSet()
        val restore = (journal.suspendedByUs - toSuspend).intersect(installedIds)
        // Write intent before mutation, so recovery can undo a partially applied batch.
        save(journal.copy(suspendedByUs = journal.suspendedByUs + toSuspend))
        val failedSuspend = if (toSuspend.isEmpty()) emptyArray() else dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
        val failedRestore = if (restore.isEmpty()) emptyArray() else dpm.setPackagesSuspended(admin, restore.toTypedArray(), false)
        check(failedSuspend.isEmpty() && failedRestore.isEmpty()) { "ОС отказала в переключении пакетов: ${(failedSuspend + failedRestore).joinToString()}" }
        save(readJournal().copy(suspendedByUs = toSuspend + (journal.suspendedByUs - installedIds)))
    }
    fun enter(activity: Activity) {
        if (!ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && dpm.isLockTaskPermitted(ctx.packageName)) activity.startLockTask()
    }
    fun launch(activity: Activity, pkg: String) {
        require(dpm.isLockTaskPermitted(pkg))
        val i = requireNotNull(ctx.packageManager.getLaunchIntentForPackage(pkg))
        activity.startActivity(i, ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle())
    }
    /** Checkpoint after every idempotent stage. Never wipes user data or enables ADB. */
    @Suppress("DEPRECATION")
    fun deprovision(initial: State, saveState: (State) -> Unit): State {
        require(initial.removal == Removal.DEPROVISIONING)
        check(BuildConfig.LAB_DEVICE) { "Нет подтверждённого production-пути снятия DO" }
        var s = initial
        fun stage(n: Int, action: () -> Unit) { if (s.deprovisionStage < n) { action(); s = s.copy(deprovisionStage = n, error = ""); saveState(s) } }
        val j = if (journalFile.baseFile.exists()) readJournal() else null
        stage(1) { if (isOwner) dpm.setLockTaskPackages(admin, emptyArray()) }
        stage(2) {
            if (isOwner && j != null) {
                val present = ctx.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
                val restore = j.suspendedByUs.intersect(present)
                if (restore.isNotEmpty()) check(dpm.setPackagesSuspended(admin, restore.toTypedArray(), false).isEmpty()) { "Не все пакеты восстановлены" }
            }
        }
        stage(3) { if (isOwner) dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) }
        stage(4) {
            if (isOwner && j != null) {
                j.restrictions.forEach { (r, was) -> if (!was) dpm.clearUserRestriction(admin, r) }
                dpm.setUninstallBlocked(admin, ctx.packageName, j.uninstallBlocked)
                dpm.setAutoTimeEnabled(admin, j.autoTime); dpm.setAutoTimeZoneEnabled(admin, j.autoZone)
                dpm.setLockTaskFeatures(admin, j.lockFeatures); dpm.setLockTaskPackages(admin, j.lockPackages.toTypedArray())
            }
        }
        stage(5) { if (isOwner) dpm.clearDeviceOwnerApp(ctx.packageName); check(!isOwner) { "Device Owner остался" } }
        stage(6) {
            check(!isOwner) { "Device Owner остался активен" }
            // Some implementations remove the owner before asynchronously removing admin.
            // This never substitutes for removing Device Owner; read back both states.
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
            check(!dpm.isAdminActive(admin)) { "Admin ещё удаляется; повторите проверку стадии 6" }
            check(ctx.getSystemService(android.app.ActivityManager::class.java).lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) { "Lock Task ещё не завершён" }
            if (j != null) check(j.suspendedByUs.none { runCatching { ctx.packageManager.isPackageSuspended(it) }.getOrDefault(false) }) { "Пакеты ещё заблокированы" }
        }
        return s.copy(removal = Removal.COMPLETE, activated = false, error = "").also(saveState)
    }
}
