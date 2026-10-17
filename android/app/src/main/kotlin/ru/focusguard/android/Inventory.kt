package ru.focusguard.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.view.inputmethod.InputMethodManager
import ru.focusguard.core.AppPin
import java.security.MessageDigest

data class Installed(val label: String, val pkg: String, val certificates: List<String>, val system: Boolean) {
    fun pin() = AppPin(pkg, certificates.first())
}
class Inventory(private val ctx: Context) {
    private val pm = ctx.packageManager
    fun certificates(pkg: String): List<String> {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
        val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signatures.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    }
    fun all(): List<Installed> = pm.getInstalledApplications(0).mapNotNull { app ->
        val cert = runCatching { certificates(app.packageName) }.getOrDefault(emptyList())
        if (cert.isEmpty()) null else Installed(pm.getApplicationLabel(app).toString(), app.packageName, cert,
            app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
    }.sortedBy { it.label.lowercase() }
    fun launchable(): Set<String> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
    fun dependencies(installed: List<Installed> = all()): Set<String> {
        val packages = mutableSetOf(ctx.packageName)
        ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage?.let { packages += it }
        android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)?.let { packages += it }
        packages += pm.queryIntentServices(Intent(android.telecom.InCallService.SERVICE_INTERFACE), PackageManager.MATCH_SYSTEM_ONLY).map { it.serviceInfo.packageName }
        val intents = listOfNotNull(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:")),
            Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI),
            Intent("com.android.phone.EmergencyDialer.DIAL"),
            runCatching { android.net.VpnService.prepare(ctx) }.getOrNull())
        intents.forEach { i -> pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { packages += it } }
        packages += ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        // These are platform components, never brand based user-app exceptions.
        packages += installed.filter { it.system && it.pkg in setOf("com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.android.vpndialogs", "com.android.permissioncontroller", "com.google.android.permissioncontroller") }.map { it.pkg }
        packages -= settingsPackages()
        return packages
    }
    fun settingsPackages(): Set<String> = pm.queryIntentActivities(Intent(android.provider.Settings.ACTION_SETTINGS), 0).map { it.activityInfo.packageName }.toSet()
    fun verified(pins: List<AppPin>): Set<String> = pins.filter { pin ->
        runCatching { certificates(pin.packageId).contains(pin.certificateSha256) }.getOrDefault(false)
    }.map { it.packageId }.toSet()
}
