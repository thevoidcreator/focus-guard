package ru.focusguard.core

import org.junit.jupiter.api.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

class GoldenTest {
    @Test fun crossLanguageVectors() {
        val folder = File(System.getProperty("repoRoot"), "protocol/vectors")
        if (System.getenv("FOCUSGUARD_GENERATE_VECTORS") == "1") {
            val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val spki = Protocol.encode(key.public.encoded)
            val m = Message(messageType = "removal_start", installationId = "vector-phone", requestId = "kotlin-vector",
                nonce = Protocol.encode(ByteArray(32) { it.toByte() }), keyId = Protocol.keyId(spki), policyVersion = 1,
                removalFlowId = "vector-flow", phase = "request_start", phoneKeyId = Protocol.keyId(spki))
            val compact = Protocol.sign(m, key.private)
            File(folder, "kotlin.json").writeText(buildJsonObject { put("publicSpki", spki); put("compact", compact); put("requestDigest", Protocol.digest(compact)) }.toString())
        }
        for (name in listOf("python.json", "kotlin.json", "dotnet.json")) {
            val file = File(folder, name)
            if (!file.exists() && System.getenv("FOCUSGUARD_GENERATE_VECTORS") == "1") continue
            val v = Protocol.json.parseToJsonElement(file.readText()).jsonObject
            val compact = v.getValue("compact").jsonPrimitive.content
            assertEquals(v.getValue("requestDigest").jsonPrimitive.content, Protocol.digest(compact))
            assertEquals("vector-phone", Protocol.verify(compact, v.getValue("publicSpki").jsonPrimitive.content).installationId)
        }
    }
}
