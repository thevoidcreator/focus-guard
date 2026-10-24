package ru.focusguard.android

import android.os.SystemClock
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** TLS-validated, cache-busted Date from two independent operators. No local clock override. */
object NetworkTime {
    fun now(): Long {
        val sources = listOf("https://dl.google.com/android/repository/repository2-1.xml", "https://builds.dotnet.microsoft.com/dotnet/release-metadata/releases-index.json")
        val samples = sources.map { source ->
            val before = SystemClock.elapsedRealtime()
            val c = URL("$source?fgtime=${java.util.UUID.randomUUID()}").openConnection() as HttpsURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false; c.instanceFollowRedirects = false
                c.setRequestProperty("Cache-Control", "no-cache, no-store"); c.requestMethod = "HEAD"
                require(c.responseCode in 200..299) { "Источник времени недоступен" }
                val t = c.getHeaderFieldDate("Date", 0)
                require(t > 0 && c.getHeaderFieldLong("Age", 0) <= 1 && SystemClock.elapsedRealtime() - before < 10_000) { "Источник вернул несвежее время" }
                t to SystemClock.elapsedRealtime()
            } finally { c.disconnect() }
        }
        val end = SystemClock.elapsedRealtime()
        val times = samples.map { it.first + end - it.second }
        require(kotlin.math.abs(times[0] - times[1]) < 60_000) { "Источники времени не согласны" }
        return times.min()
    }
}
