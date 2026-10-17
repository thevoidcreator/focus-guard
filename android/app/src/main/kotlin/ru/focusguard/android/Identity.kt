package ru.focusguard.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ru.focusguard.core.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

class Identity {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!ks.containsAlias("phone-identity-v1")) KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder("phone-identity-v1", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
    }
    val publicSpki: String get() = Protocol.encode(ks.getCertificate("phone-identity-v1").publicKey.encoded)
    val keyId: String get() = Protocol.keyId(publicSpki)
    fun sign(m: Message): String = Protocol.sign(m, ks.getKey("phone-identity-v1", null) as PrivateKey)
}
