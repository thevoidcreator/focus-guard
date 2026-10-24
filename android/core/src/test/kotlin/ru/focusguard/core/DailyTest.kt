package ru.focusguard.core

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.io.File
import kotlinx.serialization.encodeToString

class DailyTest {
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val phone = key(); private val guardian = key(); private val usb = key()
    private fun spki(k: java.security.KeyPair) = Protocol.encode(k.public.encoded)
    private val epoch = Instant.parse("2026-10-24T09:00:00Z").toEpochMilli()
    private fun reading(mono: Long = 1000, boot: Long = 1) = Reading(boot, mono, epoch + mono - 1000)
    private fun fresh() = State("phone", policy = Policy(mainSpki = spki(usb)), activated = true,
        daily = DailyEngine.tick(DailyEngine.sync(DailyState(guardian = spki(guardian)), reading(), epoch), reading(), false))
    private fun request(s: State, action: String, signer: java.security.KeyPair = guardian, replacement: String = "", policy: Policy? = null): State {
        val d = s.daily!!; val now = d.time(reading(d.mono, d.boot))!!
        val m = Approval(action = action, phoneId = s.installationId, phoneSpki = spki(phone), signerSpki = spki(signer), day = d.day, issued = now, expires = now + 60 * MINUTE, policyVersion = s.policyVersion, replacement = replacement, policy = policy)
        return s.copy(daily = d.copy(pending = CompanionProtocol.sign(m, phone.private)))
    }
    private fun answer(s: State, key: java.security.KeyPair = guardian): String {
        val token = s.daily!!.pending; val m = CompanionProtocol.request(token)
        return CompanionProtocol.sign(m.copy(phase = "response", digest = Protocol.digest(token)), key.private)
    }
    @Test fun cumulativeUnionIntervalsAndPause() {
        var d = fresh().daily!!
        d = DailyEngine.tick(d, reading(1000), true)
        d = DailyEngine.tick(d, reading(1000 + 30 * MINUTE), false)
        d = DailyEngine.tick(d, reading(1000 + 90 * MINUTE), true)
        d = DailyEngine.tick(d, reading(1000 + 180 * MINUTE), false)
        assertEquals(SESSION, d.used); assertEquals(0, d.remaining)
    }
    @Test fun audioScreenOffPipAndExemptions() {
        val allowed = setOf("whatsapp", "sms", "calls", "vpn", "guard")
        assertFalse(DailyEngine.charge(setOf("whatsapp"), setOf("whatsapp"), allowed, true, false))
        assertFalse(DailyEngine.charge(setOf("browser"), emptySet(), allowed, false, false))
        assertTrue(DailyEngine.charge(emptySet(), setOf("podcast"), allowed, false, false))
        assertTrue(DailyEngine.charge(setOf("video", "whatsapp"), emptySet(), allowed, true, false))
        assertFalse(DailyEngine.charge(emptySet(), setOf("whatsapp"), allowed, false, false))
        assertTrue(DailyEngine.charge(emptySet(), emptySet(), allowed, false, true))
        var d = fresh().daily!!
        d = DailyEngine.tick(d, reading(), DailyEngine.charge(setOf("browser"), setOf("podcast"), allowed, true, false))
        d = DailyEngine.tick(d, reading(1000 + 30 * MINUTE), false)
        assertEquals(30 * MINUTE, d.used) // foreground + audio share one budget interval
    }
    @Test fun resetAtFourMoscowAndNoCarry() {
        val before = Instant.parse("2026-10-25T00:59:00Z").toEpochMilli()
        var d = DailyEngine.tick(DailyEngine.sync(DailyState(), reading(), before), reading(), true).copy(used = SESSION, bonus = 60 * MINUTE, unlimited = true)
        val oldDay = d.day
        d = DailyEngine.tick(d, reading(1000 + 2 * MINUTE), false)
        assertEquals(oldDay + 1, d.day); assertEquals(MINUTE, d.used); assertEquals(0, d.bonus); assertFalse(d.unlimited)
    }
    @Test fun rebootCannotResetBudgetOrTrustLocalWall() {
        val d = fresh().daily!!.copy(used = 70 * MINUTE, bonus = 60 * MINUTE)
        val next = DailyEngine.tick(d, Reading(2, 500, epoch + 100 * DELAY), false)
        assertEquals(d.day, next.day); assertEquals(d.used, next.used); assertNull(next.time(reading(500, 2)))
    }
    @Test fun localClockJumpIsIgnored() {
        val d = fresh().daily!!
        val next = DailyEngine.tick(d, Reading(1, 2000, epoch + 100 * DELAY), false)
        assertEquals(d.day, next.day); assertEquals(epoch + 1000, next.time(reading(2000)))
        assertFails { DailyEngine.sync(next, reading(2000), epoch - DELAY) }
    }
    @Test fun hourIsSingleUseAndBoundToRequest() {
        val s = request(fresh(), "hour"); val token = answer(s)
        val next = DailyEngine.apply(s, token, reading())
        assertEquals(60 * MINUTE, next.daily!!.bonus)
        assertFails { DailyEngine.apply(next, token, reading()) }
        assertFails { DailyEngine.apply(request(fresh(), "hour"), token, reading()) }
        assertFails { DailyEngine.apply(s, answer(s, usb), reading()) }
    }
    @Test fun tamperActionAndOtherPhoneRejected() {
        val s = request(fresh(), "hour"); val pending = s.daily!!.pending; val m = CompanionProtocol.request(pending)
        for (bad in listOf(m.copy(action = "remove"), m.copy(phoneId = "other"), m.copy(policyVersion = 2))) {
            val token = CompanionProtocol.sign(bad.copy(phase = "response", digest = Protocol.digest(pending)), guardian.private)
            assertFails { DailyEngine.apply(s, token, reading()) }
        }
    }
    @Test fun expiryAndCancellation() {
        val s = request(fresh(), "hour"); val token = answer(s)
        assertFails { DailyEngine.apply(s, token, reading(1000 + 60 * MINUTE)) }
        assertFails { DailyEngine.apply(s.copy(daily = s.daily!!.copy(pending = "")), token, reading()) }
    }
    @Test fun guardianDayDoesNotRemoveAndRemoveIsSeparate() {
        val s = request(fresh(), "day")
        val opened = DailyEngine.apply(s, answer(s), reading())
        assertTrue(opened.daily!!.unlimited); assertEquals(Removal.NONE, opened.removal)
        val removal = request(opened, "remove")
        assertEquals(Removal.DEPROVISIONING, DailyEngine.apply(removal, answer(removal), reading()).removal)
    }
    @Test fun calendarRecoveryIncludesPoweredOffTimeAndNeedsUsb() {
        var s = request(fresh(), "usb_start_replace", usb)
        assertFails { DailyEngine.apply(s, answer(s), reading()) }
        s = DailyEngine.apply(s, answer(s, usb), reading())
        val started = s.daily!!.recoveryAt
        val after = reading(500, 2)
        val trusted = DailyEngine.sync(s.daily!!, after, started + DELAY)
        s = s.copy(daily = DailyEngine.tick(trusted, after, false))
        s = request(s, "usb_finish_replace", usb, started.toString())
        val result = DailyEngine.apply(s, answer(s, usb), after)
        assertTrue(result.daily!!.replacementAllowed); assertEquals("", result.daily!!.guardian)
        assertTrue(result.activated); assertEquals(Removal.NONE, result.removal)
    }
    @Test fun earlyRecoveryAndWrongFlowRejected() {
        var s = request(fresh(), "usb_start_remove", usb)
        s = DailyEngine.apply(s, answer(s, usb), reading())
        s = request(s, "usb_finish_remove", usb, s.daily!!.recoveryAt.toString())
        assertFails { DailyEngine.apply(s, answer(s, usb), reading()) }
    }
    @Test fun guardianCannotReplaceUsbWithPolicy() {
        val s = request(fresh(), "policy", policy = fresh().policy.copy(mainSpki = spki(guardian)))
        assertFails { DailyEngine.apply(s, answer(s), reading()) }
    }
    @Test fun enrollmentAfterActivationOnlyThroughRecovery() {
        val s = request(fresh(), "pair")
        assertFails { DailyEngine.apply(s, answer(s), reading()) }
        val freshPair = s.copy(activated = false)
        assertEquals(spki(guardian), DailyEngine.apply(freshPair, answer(freshPair), reading()).daily!!.guardian)
        val usbPair = request(fresh(), "usb_pair", usb)
        assertFails { DailyEngine.apply(usbPair, answer(usbPair, usb), reading()) }
    }
    @Test fun oldMessagesAndUnknownFieldsAreNotAccepted() {
        val s = request(fresh(), "hour"); val token = s.daily!!.pending
        val parts = token.split('.')
        val oldHeader = Protocol.encode("{\"alg\":\"ES256\",\"typ\":\"FG1\"}".toByteArray())
        assertFails { CompanionProtocol.inspect(oldHeader + "." + parts[1] + "." + parts[2]) }
        assertFails { CompanionProtocol.readFile("{\"version\":2,\"token\":\"a\",\"extra\":true}") }
        assertNotEquals(CompanionProtocol.phrase(spki(phone), spki(guardian)), CompanionProtocol.phrase(spki(phone), spki(usb)))
    }
    @Test fun kotlinInteropArtifact() {
        val s = request(fresh(), "usb_start_remove", usb)
        val token = s.daily!!.pending
        val vector = kotlinx.serialization.json.buildJsonObject {
            put("phoneSpki", kotlinx.serialization.json.JsonPrimitive(spki(phone)))
            put("usbSpki", kotlinx.serialization.json.JsonPrimitive(spki(usb)))
            put("request", kotlinx.serialization.json.JsonPrimitive(token))
            put("response", kotlinx.serialization.json.JsonPrimitive(answer(s, usb)))
        }
        File("build/fg2-kotlin.json").apply { parentFile.mkdirs(); writeText(vector.toString()) }
    }
    @Test fun dotnetVectorWhenPresent() {
        val root = System.getProperty("repoRoot")
        val file = File(root, "protocol/vectors/fg2-dotnet.json")
        assertTrue(file.exists(), "Generate and commit .NET FG2 vector")
        val obj = Protocol.json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        val token = (obj["token"] as kotlinx.serialization.json.JsonPrimitive).content
        val spki = (obj["publicSpki"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("usb_start_remove", CompanionProtocol.verify(token, spki).action)
    }
}
