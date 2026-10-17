package ru.focusguard.android

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.util.AtomicFile
import ru.focusguard.core.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString

class Storage(context: Context) {
    private val ctx = context.createDeviceProtectedStorageContext()
    private val file = AtomicFile(File(ctx.filesDir, "guard-state.json"))
    fun read(): State = if (file.baseFile.exists()) Protocol.json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
        else State(UUID.randomUUID().toString()).also { write(it) }
    fun write(s: State) {
        val out = file.startWrite()
        try { out.write(Protocol.json.encodeToString(s).toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun audit(event: String) {
        val f = File(ctx.filesDir, "audit.log")
        if (f.exists() && f.length() > 128 * 1024) f.writeText(f.readText().takeLast(64 * 1024))
        f.appendText("${SystemClock.elapsedRealtime()} ${event.replace('\n', ' ').take(300)}\n")
    }
    fun reading(): Reading = Reading(Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong(),
        SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 0) == 1 &&
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1)
    companion object { val lock = Any() }
}
