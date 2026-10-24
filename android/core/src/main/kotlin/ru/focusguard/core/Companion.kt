package ru.focusguard.core

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.PrivateKey
import java.util.UUID

/** FG2 is deliberately separate from legacy FG1 USB messages. */
@Serializable data class Approval(
    val version: Int = 2, val action: String, val phoneId: String, val phoneSpki: String,
    val signerSpki: String, val requestId: String = UUID.randomUUID().toString(),
    val nonce: String = Protocol.nonce(), val phase: String = "request",
    val digest: String = "", val day: Long, val issued: Long, val expires: Long,
    val policyVersion: Long, val policy: Policy? = null, val replacement: String = ""
)
@Serializable data class ApprovalFile(val version: Int = 2, val token: String)
object CompanionProtocol {
    fun sign(m: Approval, key: PrivateKey): String {
        val j = JWSObject(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("FG2")).build(), Payload(Protocol.json.encodeToString(m)))
        j.sign(ECDSASigner(key, com.nimbusds.jose.jwk.Curve.P_256)); return j.serialize()
    }
    fun inspect(token: String): Approval {
        require(token.length in 1..Protocol.MAX_INPUT)
        val parts = token.split('.'); require(parts.size == 3 && Protocol.decode(parts[2]).size == 64)
        val header = Protocol.decode(parts[0]).toString(Charsets.UTF_8); Protocol.strictJson(header)
        require(Protocol.json.parseToJsonElement(header) == Protocol.json.parseToJsonElement("{\"alg\":\"ES256\",\"typ\":\"FG2\"}"))
        val text = Protocol.decode(parts[1]).toString(Charsets.UTF_8); Protocol.strictJson(text)
        require((Protocol.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject).keys == setOf("version", "action", "phoneId", "phoneSpki", "signerSpki", "requestId", "nonce", "phase", "digest", "day", "issued", "expires", "policyVersion", "policy", "replacement"))
        val m = Protocol.json.decodeFromString<Approval>(text)
        require(m.version == 2 && m.phase in setOf("request", "response"))
        require(m.action in setOf("pair", "usb_pair", "hour", "day", "remove", "policy", "usb_start_remove", "usb_finish_remove", "usb_start_replace", "usb_finish_replace"))
        require(m.phoneId.length in 1..64 && m.requestId.length in 1..64 && Protocol.decode(m.nonce).size == 32)
        Protocol.publicKey(m.phoneSpki); Protocol.publicKey(m.signerSpki)
        require(m.expires > m.issued && m.expires - m.issued <= 24 * 60 * MINUTE && m.policyVersion > 0)
        return m
    }
    fun verify(token: String, spki: String): Approval {
        val m = inspect(token)
        require(JWSObject.parse(token).verify(ECDSAVerifier(Protocol.publicKey(spki)))) { "Подпись не совпала" }
        return m
    }
    fun request(token: String): Approval = inspect(token).also {
        require(it.phase == "request" && it.digest.isEmpty()); verify(token, it.phoneSpki)
    }
    fun file(token: String) = Protocol.json.encodeToString(ApprovalFile(token = token))
    fun readFile(text: String): String {
        Protocol.strictJson(text)
        require((Protocol.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject).keys == setOf("version", "token"))
        return Protocol.json.decodeFromString<ApprovalFile>(text).also { require(it.version == 2) }.token
    }
    fun phrase(phone: String, guardian: String): String = Protocol.digest("FG2-pair|$phone|$guardian").take(20).chunked(4).joinToString(" ")
    fun label(m: Approval): String = when(m.action) {
        "pair" -> "Связать телефоны"
        "usb_pair" -> "Зарегистрировать флешку восстановления"
        "hour" -> "Добавить 1 час сегодня"
        "day" -> "Разблокировать до 04:00 МСК"
        "remove" -> "Снять управление и разрешить удаление Focus Guard"
        "policy" -> "Изменить постоянный лимит / исключения"
        "usb_start_remove" -> "Начать ожидание 48 часов для удаления"
        "usb_finish_remove" -> "Завершить ожидание и разрешить удаление"
        "usb_start_replace" -> "Начать ожидание 48 часов для замены доверенного человека"
        else -> "Завершить замену доверенного человека"
    }
}

@Serializable data class DailyState(
    val guardian: String = "", val limit: Long = SESSION, val day: Long = Long.MIN_VALUE,
    val used: Long = 0, val bonus: Long = 0, val unlimited: Boolean = false,
    val boot: Long = -1, val mono: Long = 0, val charging: Boolean = false,
    val trustedEpoch: Long = 0, val trustedMono: Long = 0, val trustedBoot: Long = -1,
    val pending: String = "", val recoveryAction: String = "", val recoveryAt: Long = 0,
    val replacementAllowed: Boolean = false
) {
    fun time(r: Reading): Long? = if (trustedBoot == r.boot && r.mono >= trustedMono && trustedEpoch > 0) trustedEpoch + r.mono - trustedMono else null
    val remaining: Long get() = maxOf(0, limit + bonus - used)
}
object DailyEngine {
    fun charge(visible: Set<String>, playing: Set<String>, allowed: Set<String>, screenUsable: Boolean, unknownMusic: Boolean): Boolean =
        playing.any { it !in allowed } || unknownMusic || screenUsable && visible.any { it !in allowed }
    fun sync(d: DailyState, r: Reading, epoch: Long): DailyState {
        require(epoch > 0)
        val current = d.time(r)
        // Never move an already authenticated monotonic clock backwards.
        require(current == null || epoch >= current - 60_000) { "Сетевое время пошло назад" }
        return d.copy(trustedEpoch = maxOf(epoch, current ?: 0), trustedMono = r.mono, trustedBoot = r.boot)
    }
    fun tick(d: DailyState, r: Reading, charging: Boolean): DailyState {
        val t = d.time(r)
        val nextDay = t?.let { accountDay(it, "Europe/Moscow") }
        var n = d
        if (d.boot == r.boot && r.mono >= d.mono && d.charging) n = n.copy(used = minOf(Long.MAX_VALUE / 2, n.used + r.mono - d.mono))
        if (nextDay != null && nextDay > d.day) {
            // Attribute at most the post-boundary interval to the new day.
            val boundary = java.time.LocalDate.ofEpochDay(nextDay).atTime(4, 0).atZone(java.time.ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
            val newUse = if (d.boot == r.boot && d.charging) minOf(maxOf(0, t - boundary), maxOf(0, r.mono - d.mono)) else 0
            n = n.copy(day = nextDay, used = newUse, bonus = 0, unlimited = false, pending = "")
        }
        return n.copy(boot = r.boot, mono = r.mono, charging = charging)
    }
    fun apply(s: State, token: String, r: Reading): State {
        val d = requireNotNull(s.daily); require(d.pending.isNotEmpty()) { "Нет ожидающего запроса" }
        val req = CompanionProtocol.request(d.pending)
        val now = requireNotNull(d.time(r)) { "Сначала проверьте сетевое время" }
        require(now in req.issued until req.expires) { "Запрос истёк. Создайте новый" }
        require(req.phoneId == s.installationId && req.policyVersion == s.policyVersion)
        require(CompanionProtocol.verify(token, req.signerSpki) == req.copy(phase = "response", digest = Protocol.digest(d.pending))) { "Ответ относится к другому запросу" }
        val clean = d.copy(pending = "")
        val usb = req.action.startsWith("usb_")
        if (req.action !in setOf("pair", "usb_pair")) require(s.activated)
        if (usb) require(r.boot == d.trustedBoot && r.mono - d.trustedMono in 0..2 * MINUTE) { "Для флешки нужна свежая проверка сетевого времени" }
        if (req.action !in setOf("pair", "usb_pair")) require(req.signerSpki == if (usb) s.policy.mainSpki else d.guardian)
        return when(req.action) {
            "pair" -> { require(!s.activated || d.replacementAllowed); s.copy(daily = clean.copy(guardian = req.signerSpki, replacementAllowed = false)) }
            "usb_pair" -> { require(!s.activated); s.copy(policy = s.policy.copy(mainSpki = req.signerSpki), daily = clean) }
            "hour" -> { require(req.day == d.day); s.copy(daily = clean.copy(bonus = d.bonus + 60 * MINUTE)) }
            "day" -> { require(req.day == d.day); s.copy(daily = clean.copy(unlimited = true)) }
            "remove" -> s.copy(daily = clean, removal = Removal.DEPROVISIONING)
            "policy" -> {
                val p = requireNotNull(req.policy); Engine.validatePolicy(p)
                require(p.mainSpki == s.policy.mainSpki && p.emergencySpki == s.policy.emergencySpki && p.zone == "Europe/Moscow")
                s.copy(policy = p, policyVersion = s.policyVersion + 1, daily = clean.copy(limit = p.sessionMs))
            }
            "usb_start_remove", "usb_start_replace" -> {
                require(d.recoveryAt == 0L) { "Ожидание уже идёт" }
                s.copy(daily = clean.copy(recoveryAction = req.action, recoveryAt = req.issued))
            }
            "usb_finish_remove", "usb_finish_replace" -> {
                require(d.recoveryAt > 0 && now - d.recoveryAt >= DELAY) { "48 часов ещё не прошли" }
                require(req.replacement == d.recoveryAt.toString() && req.action.replace("finish", "start") == d.recoveryAction)
                val done = clean.copy(recoveryAt = 0, recoveryAction = "")
                if (req.action == "usb_finish_remove") s.copy(daily = done, removal = Removal.DEPROVISIONING)
                else s.copy(daily = done.copy(guardian = "", replacementAllowed = true), policyVersion = s.policyVersion + 1)
            }
            else -> error("Неизвестное действие")
        }
    }
}
