package ru.focusguard.guardian

import android.app.*
import android.os.Bundle
import android.content.*
import android.net.Uri
import android.security.keystore.*
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ru.focusguard.core.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.*
import java.security.spec.ECGenParameterSpec

class GuardianActivity : ComponentActivity() {
    private var input by mutableStateOf("")
    private var error by mutableStateOf("")
    private var output by mutableStateOf("")
    private var authorisedToken: String? = null
    private val registry get() = AtomicFile(File(filesDir, "phones-v2.json"))
    private fun pins(): Map<String,String> = if (registry.baseFile.exists()) Protocol.json.decodeFromString(registry.openRead().bufferedReader().use { it.readText() }) else emptyMap()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun publicKey(): String {
        val ks = keyStore()
        if (!ks.containsAlias("guardian-v2")) {
            require(getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Сначала установите PIN-код блокировки этого телефона" }
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder("guardian-v2", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true).setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG).build())
            }.generateKeyPair()
        }
        return Protocol.encode(ks.getCertificate("guardian-v2").publicKey.encoded)
    }
    private val auth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = authorisedToken; authorisedToken = null
        if (result.resultCode == RESULT_OK && token != null) runCatching {
            val m = validate(token)
            val signed = CompanionProtocol.sign(m.copy(phase = "response", digest = Protocol.digest(token)), keyStore().getKey("guardian-v2", null) as PrivateKey)
            if (m.action == "pair") {
                val map = pins() + (m.phoneId to m.phoneSpki)
                val stream = registry.startWrite()
                try { stream.write(Protocol.json.encodeToString(map).toByteArray()); registry.finishWrite(stream) } catch(e: Exception) { registry.failWrite(stream); throw e }
            }
            output = CompanionProtocol.file(signed); error = "Подтверждение готово. Отправьте его обратно этому человеку."; input = ""
        }.onFailure { output = ""; error = it.message ?: "Подпись не выполнена" }
        else error = "Подтверждение отменено"
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }
    private fun load(uri: Uri) { runCatching {
        input = Protocol.readStream(requireNotNull(contentResolver.openInputStream(uri)))
        output = ""; error = ""
    }.onFailure { error = "Не удалось открыть файл: ${it.message}" } }
    private fun accept(intent: Intent?) { (intent?.data ?: intent?.let { androidx.core.content.IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) } ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.let(::load)
        if (intent?.data == null && intent?.getStringExtra(Intent.EXTRA_TEXT) != null) {
            val code = intent.getStringExtra(Intent.EXTRA_TEXT)!!.trim()
            if (code.toByteArray().size <= Protocol.MAX_INPUT) { input = code; output = ""; error = "" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); accept(intent); setContent { MaterialTheme { Surface { Screen() } } } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun validate(token: String): Approval {
        val m = CompanionProtocol.request(token)
        require(m.action in setOf("pair", "hour", "day", "remove", "policy")) { "Этот запрос предназначен для флешки, а не для вас" }
        require(m.signerSpki == publicKey()) { "Запрос адресован другому доверенному устройству" }
        val old = pins()[m.phoneId]
        require(old == null && m.action == "pair" || old == m.phoneSpki) { "Телефон не связан или его идентичность изменилась" }
        require(System.currentTimeMillis() in m.issued - 60_000 until m.expires) { "Проверьте дату: запрос истёк или ещё не действителен" }
        return m
    }
    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp").putExtra(Intent.EXTRA_TEXT, text)
        require(send.resolveActivity(packageManager) != null) { "Установите WhatsApp" }; startActivity(send)
    }
    @Composable fun Screen() {
        var checked by remember(input) { mutableStateOf(false) }
        val request = remember(input) { runCatching { validate(CompanionProtocol.readFile(input)) }.getOrNull() }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Focus Guard\nПодтверждение", style = MaterialTheme.typography.headlineMedium)
            Text("Вы разрешаете дополнительное время или снятие защиты на связанном телефоне. Приложение не ограничивает ваш телефон.")
            if (error.isNotEmpty()) Text(error)
            Button(onClick = { runCatching { publicKey(); share(buildJsonObject { put("version", 2); put("kind", "guardian-invite"); put("publicSpki", publicKey()) }.toString()) }.onFailure { error = it.message ?: "Ошибка" } }) { Text("Отправить приглашение через WhatsApp") }
            Button(onClick = { runCatching {
                val c = getSystemService(android.content.ClipboardManager::class.java)
                val code = c.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@GuardianActivity)?.toString()?.trim().orEmpty()
                require(code.isNotEmpty()) { "Сначала скопируйте запрос из мессенджера" }
                Protocol.strictJson(code); input = code; output = ""; error = ""
            }.onFailure { error = it.message ?: "Не удалось вставить запрос" } }) { Text("Вставить запрос") }
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Открыть запрос из WhatsApp") }
            if (input.isNotEmpty() && request == null) {
                Text("Запрос не прошёл проверку")
                Button(onClick = { runCatching { validate(CompanionProtocol.readFile(input)) }.onFailure { error = it.message ?: "Некорректный файл" } }) { Text("Показать причину") }
            }
            if (request != null) {
                Text(CompanionProtocol.label(request), style = MaterialTheme.typography.titleLarge)
                Text("Телефон: ${request.phoneId}")
                Text("Действует до: ${java.time.Instant.ofEpochMilli(request.expires).atZone(java.time.ZoneId.systemDefault())}")
                if (request.action == "pair") Text("Сверьте голосом фразу с владельцем телефона:\n${CompanionProtocol.phrase(request.phoneSpki, request.signerSpki)}")
                if (request.action == "remove") Text("Это разрешит снять системное управление и удалить Focus Guard. Ограничения перестанут действовать.")
                request.policy?.let { p -> Text("Новый лимит: ${p.sessionMs / MINUTE} мин. Постоянные исключения:\n${p.apps.joinToString("\n") { it.packageId + "\nсертификат: " + it.certificateSha256 }}") }
                Row { Checkbox(checked, { checked = it }); Text(if (request.action == "pair") "Сверил фразу по звонку и хочу связать этот телефон" else "Проверил действие и разрешаю его") }
                Button(enabled = checked, onClick = {
                    runCatching {
                        val token = CompanionProtocol.readFile(input); validate(token); authorisedToken = token
                        val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Подтверждение Focus Guard", CompanionProtocol.label(request))
                        requireNotNull(intent) { "Настройте PIN-код телефона" }; auth.launch(intent)
                    }.onFailure { authorisedToken = null; error = it.message ?: "Ошибка" }
                }) { Text("Разрешить — подтвердить PIN-кодом телефона") }
                OutlinedButton(onClick = { input = ""; output = ""; error = "Запрос отклонён. Разрешение не выдано." }) { Text("Отказать") }
            }
            if (output.isNotEmpty()) Button(onClick = { getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Focus Guard", output)); error = "Подтверждение скопировано. Отправьте текст обратно." }) { Text("Скопировать подтверждение") }
            if (output.isNotEmpty()) Button(onClick = { runCatching { share(output) }.onFailure { error = it.message ?: "Не удалось отправить" } }) { Text("Отправить подтверждение через WhatsApp") }
            Text("Не удаляйте это приложение без переноса доверия: его переустановка создаст другую идентичность. При потере телефона владелец сможет заменить вас своим USB-ключом после 48 часов.")
        }
    }
}
