package ru.focusguard.core

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class EngineTest {
    private val phone = key(); private val main = key(); private val emergency = key()
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun spki(k: java.security.KeyPair) = Protocol.encode(k.public.encoded)
    private fun fresh(): State = Engine.tick(State("test-phone", policy = Policy(mainSpki = spki(main), emergencySpki = spki(emergency)), activated = true), Reading(1, 1000, java.time.Instant.parse("2026-01-02T02:00:00Z").toEpochMilli()))
    private fun advance(s: State, ms: Long) = Engine.tick(s, Reading(s.boot, s.lastMono + ms, s.lastWall + ms))
    private fun challenge(s: State, type: String, role: String = "main", proposal: Policy? = null) = Engine.challenge(s, type, Protocol.keyId(spki(phone)), { Protocol.sign(it, phone.private) }, role = role, proposal = proposal)
    private fun answer(s: State, signer: java.security.KeyPair = main): String = s.challenge!!.let { Protocol.sign(it.message.copy(phase = "response", requestDigest = Protocol.digest(it.compact)), signer.private) }
    @Test fun pauseCancellationDoesNotConsume() {
        val s = Engine.cancelPause(advance(Engine.beginPause(fresh()), MINUTE))
        assertEquals(Long.MIN_VALUE, s.consumedDay)
        assertFails { Engine.startSession(s) }
        assertEquals(Mode.SESSION_ACTIVE, Engine.startSession(advance(Engine.beginPause(s), MINUTE)).mode)
    }
    @Test fun screenOffExpiresAndEarlyEndBurnsDay() {
        val s = Engine.startSession(advance(Engine.beginPause(fresh()), MINUTE))
        assertEquals(Mode.LOCKED, advance(s, SESSION).mode)
        assertFails { Engine.beginPause(advance(Engine.endSession(s), COOLDOWN)) }
    }
    @Test fun midnightAndTwelveHoursDoNotGrantSecondSession() {
        val base = fresh().copy(lastWall = java.time.Instant.parse("2026-01-02T00:59:00Z").toEpochMilli())
        val s = Engine.startSession(advance(Engine.beginPause(base), MINUTE))
        assertEquals(Mode.SESSION_ACTIVE, advance(s, MINUTE).mode)
        assertFails { Engine.beginPause(advance(Engine.endSession(s), 2 * 60 * MINUTE)) }
        assertEquals(Mode.LOCKED, advance(s, SESSION).mode)
    }
    @Test fun rebootBurnsAndInvalidatesAndUntrustsDate() {
        var s = Engine.startSession(advance(Engine.beginPause(fresh()), MINUTE))
        s = challenge(s, "removal_start")
        val n = Engine.tick(s, Reading(2, 0, s.lastWall + 24 * 60 * MINUTE))
        assertNull(n.challenge); assertFalse(n.dateTrusted); assertEquals(s.consumedDay, n.consumedDay)
        assertEquals(Mode.LOCKED, n.mode); assertFails { Engine.beginPause(n) }
    }
    @Test fun wallClockJumpDoesNotShortenWaiting() {
        var s = challenge(fresh(), "removal_start")
        s = Engine.applyResponse(s, answer(s))
        s = Engine.tick(s, Reading(1, s.lastMono + MINUTE, s.lastWall + DELAY))
        assertEquals(MINUTE, s.flow!!.confirmedMs); assertFalse(s.dateTrusted)
        assertFails { challenge(s, "removal_finish") }
    }
    @Test fun completeFlowFreshFinalSameKeyReplayRejected() {
        var s = challenge(fresh(), "removal_start")
        val first = answer(s)
        s = Engine.applyResponse(s, first)
        assertEquals(Mode.LOCKED, s.mode)
        assertFails { Engine.applyResponse(s, first) }
        assertFails { challenge(advance(s, DELAY - 1), "removal_finish") }
        s = challenge(advance(s, DELAY), "removal_finish")
        assertFails { Engine.applyResponse(s, first) }
        assertFails { Engine.applyResponse(s, answer(s, emergency)) }
        assertEquals(Removal.DEPROVISIONING, Engine.applyResponse(s, answer(s)).removal)
    }
    @Test fun powerOffNeverCountsWaitingAndCancelInvalidates() {
        var s = challenge(fresh(), "removal_start")
        s = advance(Engine.applyResponse(s, answer(s)), MINUTE)
        s = Engine.tick(s, Reading(2, 0, s.lastWall + DELAY))
        assertEquals(MINUTE, s.flow!!.confirmedMs)
        assertEquals(Removal.NONE, Engine.cancelFlow(s).removal)
    }
    @Test fun emergencyRoleAndMissingKey() {
        val s = challenge(fresh(), "emergency_release", "emergency")
        assertFails { Engine.applyResponse(s, answer(s, main)) }
        assertEquals(Removal.DEPROVISIONING, Engine.applyResponse(s, answer(s, emergency)).removal)
        assertFails { challenge(fresh().copy(policy = fresh().policy.copy(emergencySpki = "")), "emergency_release", "emergency") }
    }
    @Test fun expiryDeviceDigestAndParallelResponses() {
        val s = challenge(fresh(), "removal_start")
        val response = answer(s)
        assertFails { Engine.applyResponse(advance(s, REQUEST_TTL), response) }
        val wrong = s.challenge!!.message.copy(phase = "response", requestDigest = Protocol.digest(s.challenge!!.compact), installationId = "other")
        assertFails { Engine.applyResponse(s, Protocol.sign(wrong, main.private)) }
        val newer = challenge(s, "removal_start")
        assertFails { Engine.applyResponse(newer, response) }
    }
    @Test fun policyChangeUsesDelayAndBindsProposal() {
        var s = challenge(fresh(), "policy_change", proposal = fresh().policy.copy(sessionMs = 180 * MINUTE))
        s = Engine.applyResponse(s, answer(s))
        assertEquals(SESSION, s.policy.sessionMs)
        s = challenge(advance(s, DELAY), "policy_change")
        s = Engine.applyResponse(s, answer(s))
        assertEquals(180 * MINUTE, s.policy.sessionMs); assertEquals(2, s.policyVersion)
    }
    @Test fun strictProtocol() {
        assertFails { Protocol.strictJson("{\"a\":1,\"a\":2}") }
        assertFails { Protocol.strictJson("[ [[[[[[[[]]]]]]]] ]") }
        assertFails { Protocol.strictJson("{} {}") }
        assertFails { Protocol.decode("AB") }
        val s = challenge(fresh(), "removal_start")
        assertEquals(s.challenge!!.message, Protocol.verify(s.challenge!!.compact, spki(phone)))
        val parts = s.challenge!!.compact.split('.')
        val none = Protocol.encode("{\"alg\":\"none\",\"typ\":\"FG1\"}".toByteArray()) + "." + parts[1] + "." + parts[2]
        assertFails { Protocol.verify(none, spki(phone)) }
    }
    @Test fun dateRecoveryRequiresContinuousDayAndAutomaticTime() {
        val old = fresh()
        var s = Engine.tick(old, Reading(2, 0, old.lastWall + 24 * 60 * MINUTE))
        assertFalse(s.dateTrusted)
        s = advance(s, 23 * 60 * MINUTE); assertFalse(s.dateTrusted)
        s = Engine.tick(s, Reading(s.boot, s.lastMono + MINUTE, s.lastWall + MINUTE, false))
        s = advance(s, 60 * MINUTE); assertFalse(s.dateTrusted)
        s = advance(s, 23 * 60 * MINUTE); assertTrue(s.dateTrusted)
    }
    @Test fun rollbackAndDstRespectAccountZone() {
        val s = fresh()
        val backwards = Engine.tick(s, Reading(1, s.lastMono + MINUTE, s.lastWall - COOLDOWN))
        assertFalse(backwards.dateTrusted); assertEquals(s.highDay, backwards.highDay)
        val before = java.time.Instant.parse("2026-03-29T01:59:59Z").toEpochMilli()
        val after = java.time.Instant.parse("2026-03-29T02:00:00Z").toEpochMilli()
        assertEquals(accountDay(before, "Europe/Berlin") + 1, accountDay(after, "Europe/Berlin"))
    }
    @Test fun enrollmentIsClosedAfterActivationAndRoleIsIndependent() {
        var s = fresh().copy(activated = false, policy = fresh().policy.copy(mainSpki = "", emergencySpki = ""))
        s = Engine.challenge(s, "enrollment", Protocol.keyId(spki(phone)), { Protocol.sign(it, phone.private) }, spki(main))
        s = Engine.applyResponse(s, answer(s)); assertEquals(spki(main), s.policy.mainSpki)
        s = Engine.activate(s)
        assertFails { Engine.challenge(s, "enrollment", Protocol.keyId(spki(phone)), { Protocol.sign(it, phone.private) }, spki(emergency), "emergency") }
        assertFails { challenge(s, "emergency_release", "emergency") }
    }
    @Test fun cancelFlowRejectsScreenshotsAndDoesNotOpenApps() {
        val initial = challenge(fresh(), "removal_start")
        val reply = answer(initial)
        val waiting = Engine.applyResponse(initial, reply)
        val canceled = Engine.cancelFlow(waiting)
        assertEquals(Mode.LOCKED, canceled.mode)
        val restarted = challenge(canceled, "removal_start")
        assertFails { Engine.applyResponse(restarted, reply) }
        val nextWaiting = Engine.applyResponse(restarted, answer(restarted))
        assertEquals(0, nextWaiting.flow!!.confirmedMs)
        assertNotEquals(waiting.flow!!.id, nextWaiting.flow!!.id)
    }
    @Test fun signedFieldSubstitutionAndCriticalHeaderRejected() {
        val s = challenge(fresh(), "removal_start")
        val ch = s.challenge!!
        val good = ch.message.copy(phase = "response", requestDigest = Protocol.digest(ch.compact))
        for (bad in listOf(good.copy(policyVersion = 2), good.copy(requestDigest = Protocol.nonce()),
            good.copy(phase = "request_finish"), good.copy(removalFlowId = "other-flow"), good.copy(role = "emergency"))) {
            assertFails { Engine.applyResponse(s, Protocol.sign(bad, main.private)) }
        }
        val parts = ch.compact.split('.')
        val critical = Protocol.encode("{\"alg\":\"ES256\",\"typ\":\"FG1\",\"crit\":[\"future\"]}".toByteArray()) + "." + parts[1] + "." + parts[2]
        assertFails { Protocol.inspect(critical) }
        assertFails { Protocol.inspect(Protocol.sign(ch.message.copy(protocolVersion = 2), phone.private)) }
        assertFails { Protocol.inspect("A".repeat(Protocol.MAX_INPUT + 1)) }
    }
}
