package ru.focusguard.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

const val MINUTE = 60_000L
const val SESSION = 120 * MINUTE
const val DELAY = 48 * 60 * MINUTE
const val COOLDOWN = 12 * 60 * MINUTE
const val REQUEST_TTL = 15 * MINUTE
enum class Mode { LOCKED, SESSION_ACTIVE }
enum class Removal { NONE, WAITING_48H, READY_FOR_FINAL_CONFIRMATION, DEPROVISIONING, COMPLETE }
@Serializable data class AppPin(val packageId: String, val certificateSha256: String)
@Serializable data class Policy(
    val zone: String = "Europe/Moscow", val sessionMs: Long = SESSION,
    val apps: List<AppPin> = emptyList(), val mainSpki: String = "", val emergencySpki: String = ""
)
@Serializable data class Challenge(val message: Message, val compact: String, val boot: Long, val issuedMono: Long)
@Serializable data class Flow(val id: String, val kind: String, val keyId: String, val confirmedMs: Long = 0, val proposal: Policy? = null)
@Serializable data class State(
    val installationId: String, val policy: Policy = Policy(), val policyVersion: Long = 1,
    val activated: Boolean = false, val mode: Mode = Mode.LOCKED, val removal: Removal = Removal.NONE,
    val boot: Long = -1, val lastMono: Long = 0, val lastWall: Long = 0,
    val dateTrusted: Boolean = true, val stableMs: Long = 0, val highDay: Long = Long.MIN_VALUE,
    val consumedDay: Long = Long.MIN_VALUE, val sessionDeadline: Long = 0, val cooldownMs: Long = 0,
    val pauseStarted: Long? = null, val flow: Flow? = null, val challenge: Challenge? = null,
    val deprovisionStage: Int = 0, val error: String = "", val daily: DailyState? = null
)
data class Reading(val boot: Long, val mono: Long, val wall: Long, val automatic: Boolean = true)
fun accountDay(wall: Long, zone: String): Long = Instant.ofEpochMilli(wall).atZone(ZoneId.of(zone))
    .toLocalDateTime().minusHours(4).toLocalDate().toEpochDay()
