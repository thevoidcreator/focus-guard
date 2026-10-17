package ru.focusguard.core

import java.util.UUID
import kotlin.math.abs

/** Pure transitions. Caller must persist returned state atomically before applying OS effects. */
object Engine {
    fun tick(s: State, n: Reading): State {
        require(n.mono >= 0)
        val day = accountDay(n.wall, s.policy.zone)
        if (s.boot != n.boot || n.mono < s.lastMono) {
            return s.copy(boot = n.boot, lastMono = n.mono, lastWall = n.wall,
                mode = Mode.LOCKED, sessionDeadline = 0, pauseStarted = null, challenge = null,
                dateTrusted = s.boot == -1L && n.automatic, stableMs = 0,
                highDay = maxOf(s.highDay, if (s.boot == -1L) day else s.highDay))
        }
        val delta = n.mono - s.lastMono
        val consistent = n.automatic && abs((n.wall - s.lastWall) - delta) <= 2_000 && day >= s.highDay
        val stable = if (consistent) s.stableMs + delta else 0L
        val trusted = consistent && (s.dateTrusted || stable >= 24 * 60 * MINUTE)
        val flow = s.flow?.copy(confirmedMs = minOf(DELAY, s.flow.confirmedMs + delta))
        val removal = if (s.removal == Removal.WAITING_48H && flow != null && flow.confirmedMs >= DELAY)
            Removal.READY_FOR_FINAL_CONFIRMATION else s.removal
        return s.copy(lastMono = n.mono, lastWall = n.wall, dateTrusted = trusted, stableMs = stable,
            highDay = if (trusted) maxOf(s.highDay, day) else s.highDay,
            cooldownMs = maxOf(0, s.cooldownMs - delta), flow = flow, removal = removal,
            mode = if (s.mode == Mode.SESSION_ACTIVE && n.mono < s.sessionDeadline) Mode.SESSION_ACTIVE else Mode.LOCKED,
            challenge = s.challenge?.takeIf { n.mono >= it.issuedMono && n.mono - it.issuedMono < REQUEST_TTL })
    }
    fun beginPause(s: State): State {
        eligible(s)
        return s.copy(pauseStarted = s.lastMono)
    }
    fun cancelPause(s: State) = s.copy(pauseStarted = null)
    fun startSession(s: State): State {
        eligible(s)
        require(s.pauseStarted != null && s.lastMono - s.pauseStarted >= MINUTE) { "Подождите 60 секунд и подтвердите отдельно" }
        return s.copy(mode = Mode.SESSION_ACTIVE, consumedDay = accountDay(s.lastWall, s.policy.zone),
            sessionDeadline = s.lastMono + s.policy.sessionMs, cooldownMs = COOLDOWN, pauseStarted = null)
    }
    private fun eligible(s: State) {
        require(s.activated && s.removal !in listOf(Removal.DEPROVISIONING, Removal.COMPLETE))
        require(s.mode == Mode.LOCKED && s.dateTrusted && s.cooldownMs == 0L) { "Сеанс недоступен: время или интервал 12 часов" }
        require(accountDay(s.lastWall, s.policy.zone) > s.consumedDay) { "Сегодня сеанс уже израсходован" }
    }
    fun endSession(s: State) = s.copy(mode = Mode.LOCKED, sessionDeadline = 0, pauseStarted = null)
    fun cancelFlow(s: State): State {
        require(s.removal !in listOf(Removal.DEPROVISIONING, Removal.COMPLETE))
        return s.copy(flow = null, challenge = null, removal = Removal.NONE)
    }
    fun applyResponse(s: State, compact: String): State {
        val ch = requireNotNull(s.challenge) { "Нет действующего запроса" }
        require(ch.boot == s.boot && s.lastMono - ch.issuedMono in 0 until REQUEST_TTL)
        val req = ch.message
        val spki = when {
            req.messageType == "enrollment" -> req.publicKey
            req.messageType == "emergency_release" -> s.policy.emergencySpki.also { require(it.isNotEmpty()) }
            else -> s.policy.mainSpki
        }
        val response = Protocol.verify(compact, spki)
        require(response == req.copy(phase = "response", requestDigest = Protocol.digest(ch.compact))) { "Ответ не соответствует запросу" }
        val consumed = s.copy(challenge = null)
        return when (req.messageType) {
            "enrollment" -> {
                require(!s.activated && s.flow == null)
                require(req.role in listOf("main", "emergency"))
                require(req.publicKey != if (req.role == "main") s.policy.emergencySpki else s.policy.mainSpki)
                consumed.copy(policy = if (req.role == "main") s.policy.copy(mainSpki = req.publicKey) else s.policy.copy(emergencySpki = req.publicKey))
            }
            "removal_start", "policy_change" -> if (req.phase == "request_start") {
                require(s.flow == null && s.removal == Removal.NONE)
                consumed.copy(flow = Flow(req.removalFlowId, req.messageType, req.keyId, proposal = req.proposal), removal = Removal.WAITING_48H)
            } else finish(consumed, req)
            "removal_finish" -> finish(consumed, req)
            "emergency_release" -> {
                require(req.role == "emergency" && spki != s.policy.mainSpki)
                consumed.copy(removal = Removal.DEPROVISIONING, mode = Mode.LOCKED, pauseStarted = null, flow = null)
            }
            else -> error("Неизвестное действие")
        }
    }
    private fun finish(s: State, req: Message): State {
        val f = requireNotNull(s.flow)
        require(s.removal == Removal.READY_FOR_FINAL_CONFIRMATION && f.confirmedMs >= DELAY)
        require(req.phase == "request_finish" && req.removalFlowId == f.id && req.keyId == f.keyId)
        return if (f.kind == "policy_change") {
            val policy = requireNotNull(f.proposal)
            validatePolicy(policy)
            s.copy(policy = policy, policyVersion = s.policyVersion + 1, flow = null, removal = Removal.NONE,
                mode = Mode.LOCKED, pauseStarted = null, dateTrusted = false, stableMs = 0)
        } else s.copy(removal = Removal.DEPROVISIONING, mode = Mode.LOCKED, pauseStarted = null)
    }
    fun activate(s: State): State {
        require(!s.activated && s.policy.mainSpki.isNotEmpty() && s.challenge == null)
        validatePolicy(s.policy)
        return s.copy(activated = true)
    }
    fun validatePolicy(p: Policy) {
        java.time.ZoneId.of(p.zone)
        require(p.sessionMs in MINUTE..(24 * 60 * MINUTE))
        Protocol.publicKey(p.mainSpki)
        if (p.emergencySpki.isNotEmpty()) { Protocol.publicKey(p.emergencySpki); require(p.emergencySpki != p.mainSpki) }
        require(p.apps.size <= 100 && p.apps.map { it.packageId }.distinct().size == p.apps.size)
        p.apps.forEach { require(it.packageId.matches(Regex("[a-zA-Z0-9_.]+")) && it.certificateSha256.matches(Regex("[a-f0-9]{64}"))) }
    }
    fun challenge(s: State, type: String, phoneKeyId: String, sign: (Message) -> String,
                  publicKey: String = "", role: String = "main", proposal: Policy? = null): State {
        require(s.removal !in listOf(Removal.DEPROVISIONING, Removal.COMPLETE))
        val finish = type == "removal_finish" || type == "policy_change" && s.flow != null
        if (type == "enrollment") {
            require(!s.activated && publicKey.isNotEmpty() && role in listOf("main", "emergency"))
            Protocol.publicKey(publicKey)
        } else {
            require(s.activated)
            require(type in listOf("removal_start", "removal_finish", "policy_change", "emergency_release"))
            if (type == "emergency_release") require(s.policy.emergencySpki.isNotEmpty() && role == "emergency")
            else require(role == "main")
            if (finish) require(s.removal == Removal.READY_FOR_FINAL_CONFIRMATION && s.flow?.confirmedMs == DELAY)
            else if (type != "emergency_release") require(s.flow == null)
            if (type == "policy_change" && !finish) validatePolicy(requireNotNull(proposal))
        }
        val target = when { type == "enrollment" -> publicKey; type == "emergency_release" -> s.policy.emergencySpki; else -> s.policy.mainSpki }
        val m = Message(messageType = type, installationId = s.installationId, requestId = UUID.randomUUID().toString(),
            nonce = Protocol.nonce(), keyId = Protocol.keyId(target), policyVersion = s.policyVersion,
            removalFlowId = if (finish) requireNotNull(s.flow).id else if (type in listOf("removal_start", "policy_change")) UUID.randomUUID().toString() else "",
            phase = if (finish) "request_finish" else "request_start", phoneKeyId = phoneKeyId,
            publicKey = if (type == "enrollment") publicKey else "", role = role,
            proposal = if (finish) s.flow?.proposal else proposal)
        return s.copy(challenge = Challenge(m, sign(m), s.boot, s.lastMono))
    }
}
