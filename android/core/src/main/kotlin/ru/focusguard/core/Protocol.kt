package ru.focusguard.core

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable data class Message(
    val protocolVersion: Int = 1, val messageType: String, val installationId: String,
    val requestId: String, val nonce: String, val keyId: String, val policyVersion: Long,
    val removalFlowId: String = "", val phase: String, val requestDigest: String = "",
    val role: String = "main", val phoneKeyId: String, val publicKey: String = "",
    val proposal: Policy? = null
)
object Protocol {
    const val MAX_INPUT = 16384
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun decode(s: String): ByteArray {
        require(s.matches(Regex("[A-Za-z0-9_-]*")))
        val bytes = Base64.getUrlDecoder().decode(s)
        require(encode(bytes) == s) { "Неканонический base64url" }
        return bytes
    }
    fun nonce() = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun digest(text: String) = encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    fun keyId(spki: String): String { publicKey(spki); return encode(MessageDigest.getInstance("SHA-256").digest(decode(spki))) }
    fun publicKey(spki: String): ECPublicKey {
        require(spki.length <= 256)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(spki))) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(key.params.order == expected.order && key.params.curve == expected.curve && key.params.generator == expected.generator)
        return key
    }
    fun strictJson(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT)
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().createParser(text).use { p ->
            var depth = 0
            var roots = 0
            while (p.nextToken() != null) {
                val t = p.currentToken()
                if (depth == 0) roots++
                if (t.isStructStart) depth++
                require(depth <= 8)
                if (t.isStructEnd) depth--
            }
            require(depth == 0 && roots == 1)
        }
    }
    fun inspect(compact: String): Message {
        require(compact.length in 1..MAX_INPUT && compact.all { it.code in 33..126 })
        val parts = compact.split('.')
        require(parts.size == 3 && decode(parts[2]).size == 64)
        val header = decode(parts[0]).toString(Charsets.UTF_8)
        strictJson(header)
        val obj = json.parseToJsonElement(header) as kotlinx.serialization.json.JsonObject
        require(obj.keys == setOf("alg", "typ") && obj["alg"].toString() == "\"ES256\"" && obj["typ"].toString() == "\"FG1\"")
        val payload = decode(parts[1]).toString(Charsets.UTF_8)
        strictJson(payload)
        val fields = (json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject).keys
        require(fields == setOf("protocolVersion", "messageType", "installationId", "requestId", "nonce", "keyId", "policyVersion", "removalFlowId", "phase", "requestDigest", "role", "phoneKeyId", "publicKey", "proposal"))
        val m = json.decodeFromString<Message>(payload)
        require(m.protocolVersion == 1 && m.policyVersion > 0 && decode(m.nonce).size == 32)
        require(m.messageType in listOf("enrollment", "removal_start", "removal_finish", "policy_change", "emergency_release"))
        require(m.phase in listOf("request_start", "request_finish", "response"))
        require(m.role in listOf("main", "emergency"))
        require(m.requestId.length in 1..64 && m.installationId.length in 1..64 && m.removalFlowId.length <= 64)
        require(decode(m.keyId).size == 32 && decode(m.phoneKeyId).size == 32)
        require(m.requestDigest.isEmpty() || decode(m.requestDigest).size == 32)
        return m
    }
    fun sign(m: Message, privateKey: PrivateKey): String {
        val jws = JWSObject(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("FG1")).build(), Payload(json.encodeToString(m)))
        jws.sign(ECDSASigner(privateKey, com.nimbusds.jose.jwk.Curve.P_256))
        return jws.serialize()
    }
    fun verify(compact: String, spki: String): Message {
        val m = inspect(compact)
        require(JWSObject.parse(compact).verify(ECDSAVerifier(publicKey(spki)))) { "Неверная подпись" }
        return m
    }
}
