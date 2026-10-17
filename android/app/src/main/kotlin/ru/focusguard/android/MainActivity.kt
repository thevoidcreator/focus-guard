package ru.focusguard.android

import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.*
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.*
import ru.focusguard.core.*
import ru.focusguard.core.State

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf("")
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { incoming = it } }
    private var fileCallback: ((String) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        runCatching {
            if (uri != null) contentResolver.openInputStream(uri)?.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) { val count = it.read(buffer); if (count < 0) break; require(out.size() + count <= Protocol.MAX_INPUT); out.write(buffer, 0, count) }
                fileCallback?.invoke(out.toByteArray().toString(Charsets.UTF_8))
            }
        }.onFailure { incoming = "Ошибка файла: ${it.message}" }
        runCatching { GuardRuntime(this).update() }; fileCallback = null
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.focusguard+jws")) { uri ->
        if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) } }
        exportText = ""; runCatching { GuardRuntime(this).update() }
    }
    private var exportText = ""
    private fun permitDocumentTask() {
        val c = PolicyController(this)
        if (c.isOwner && Storage(this).read().activated) {
            val handlers = packageManager.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 0)
                .map { it.activityInfo.packageName }
            c.dpm.setLockTaskPackages(c.admin, (c.dpm.getLockTaskPackages(c.admin).toList() + handlers).distinct().toTypedArray())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No incoming intent or extras trigger state transitions.
        setContent { MaterialTheme { Surface { GuardScreen() } } }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { GuardRuntime(this@MainActivity).update() } }.onSuccess { s ->
                if (s.activated && s.removal !in listOf(Removal.COMPLETE, Removal.DEPROVISIONING)) runCatching { PolicyController(this@MainActivity).enter(this@MainActivity) }
            }
        }
    }
    @Composable private fun GuardScreen() {
        val runtime = remember { GuardRuntime(this) }
        var state by remember { mutableStateOf<State?>(null) }
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("main") }
        var candidate by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var ack by remember { mutableStateOf(false) }
        var showInventory by remember { mutableStateOf(false) }
        var ssid by remember { mutableStateOf("") }; var wifiPassword by remember { mutableStateOf("") }
        val inventory = remember { Inventory(this) }
        var apps by remember { mutableStateOf(listOf<Installed>()) }
        var launchable by remember { mutableStateOf(setOf<String>()) }
        var phoneKeyId by remember { mutableStateOf("") }
        var phoneSpki by remember { mutableStateOf("") }
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        fun act(event: String, f: (State) -> State) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { runtime.update(event, f) } }
                    .onSuccess { state = it; error = "" }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }
        fun request(type: String, proposal: Policy? = null) {
            val publicKey = candidate
            val selectedRole = if (type == "emergency_release") "emergency" else role
            act("challenge $type") { s ->
                val identity = Identity()
                Engine.challenge(s, type, identity.keyId, identity::sign, publicKey, selectedRole, proposal)
            }
        }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { runtime.update() } }.onSuccess { state = it }.onFailure { error = "Хранилище: ${it.message}" }
            launch {
                runCatching { withContext(Dispatchers.IO) { inventory.all() to inventory.launchable() } }
                    .onSuccess { (installed, launches) -> apps = installed; launchable = launches }.onFailure { error = "Инвентаризация: ${it.message}" }
            }
            launch {
                runCatching { withContext(Dispatchers.IO) { Identity().let { it.keyId to it.publicSpki } } }
                    .onSuccess { (id, pub) -> phoneKeyId = id; phoneSpki = pub }.onFailure { error = "Keystore: ${it.message}" }
            }
            while (true) { delay(1000); now = SystemClock.elapsedRealtime()
                if (now / 10000 != (now - 1000) / 10000) runCatching { withContext(Dispatchers.IO) { runtime.update() } }.onSuccess { state = it }.onFailure { error = "Хранилище/политика: ${it.message}" }
            }
        }
        LaunchedEffect(state?.activated, state?.mode, state?.removal) {
            val s = state
            if (s?.activated == true && s.removal !in listOf(Removal.COMPLETE, Removal.DEPROVISIONING)) runCatching { PolicyController(this@MainActivity).enter(this@MainActivity) }
        }
        LaunchedEffect(incoming) { if (incoming.isNotEmpty()) { text = incoming; incoming = "" } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Focus Guard", style = MaterialTheme.typography.headlineLarge)
            Text(if (BuildConfig.LAB_DEVICE) "Лабораторная сборка • не для основного телефона" else "Активация закрыта до испытаний выхода на модели")
            val s = state
            if (s == null) { Text(if (error.isEmpty()) "Загрузка состояния…" else "Не удалось прочитать состояние. Защита не сброшена."); Text(error); return@Column }
            Text("Телефон: ${s.installationId}\nПолитика: ${s.policyVersion} • ${s.policy.zone}")
            Text("Идентичность телефона: ${phoneKeyId.ifEmpty { "загружается после unlock" }}")
            Text(if (s.mode == Mode.SESSION_ACTIVE) "Свободный сеанс: ${maxOf(0, s.sessionDeadline - now) / 1000} с" else "Ограниченный режим")
            if (!s.dateTrusted) Text("Дата недостоверна. Новый сеанс закрыт; требуется 24 часа непрерывной стабильной работы с автоматическим временем.")
            if (s.error.isNotEmpty()) Text(s.error, color = MaterialTheme.colorScheme.error)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (!s.activated && s.removal != Removal.COMPLETE) {
                Text("Сначала создайте USB-ключ в Windows, импортируйте его публичные метаданные и сверьте fingerprints. Аварийный ключ доверенного человека регистрируется отдельно.")
                Button(onClick = { showInventory = !showInventory }) { Text("Выбрать исключения по пакетам и сертификатам") }
                if (showInventory) {
                    Text("Выберите WhatsApp и установленные VPN вручную. Отсутствующие v2rayNG, Happ, v2RayTun, Incy не добавляются по имени. Settings выбрать нельзя.")
                    apps.filter { it.pkg !in inventory.settingsPackages() && it.pkg != packageName }.forEach { app ->
                        Row { Checkbox(app.pkg in selected, { yes -> selected = if (yes) selected + app.pkg else selected - app.pkg }); Text("${app.label}\n${app.pkg}\n${app.certificates.joinToString()}") }
                    }
                    Button(onClick = { act("initial_policy") { st -> require(!st.activated); st.copy(policy = st.policy.copy(apps = apps.filter { it.pkg in selected }.map { it.pin() })) }; showInventory = false }) { Text("Сохранить выбранные исключения") }
                }
                Row { RadioButton(role == "main", { role = "main" }); Text("Основной"); RadioButton(role == "emergency", { role = "emergency" }); Text("Аварийный") }
                Button(onClick = {
                    runCatching {
                        Protocol.strictJson(text)
                        val obj = Protocol.json.parseToJsonElement(text).jsonObject
                        require(obj["role"]!!.jsonPrimitive.content == role)
                        candidate = obj["publicSpki"]!!.jsonPrimitive.content
                        require(obj["keyId"]!!.jsonPrimitive.content == Protocol.keyId(candidate))
                    }.onFailure { error = "Метаданные: ${it.message}" }
                }) { Text("Показать fingerprint импортированного ключа") }
                if (candidate.isNotEmpty()) {
                    Text("Сверьте на USB/Windows: ${Protocol.keyId(candidate)}\nРоль: $role")
                    Button(onClick = { request("enrollment") }) { Text("Fingerprints совпали — запрос регистрации") }
                }
                Text("Основной: ${s.policy.mainSpki.takeIf { it.isNotEmpty() }?.let(Protocol::keyId) ?: "не зарегистрирован"}\nАварийный: ${s.policy.emergencySpki.takeIf { it.isNotEmpty() }?.let(Protocol::keyId) ?: "не зарегистрирован — быстрого выхода нет"}")
                Row { Checkbox(ack, { ack = it }); Text("Тестовый телефон без личных данных; резервные копии и выход проверю по инструкции. Reboot сжигает сеанс и удлиняет ожидание.") }
                Button(enabled = ack && BuildConfig.LAB_DEVICE, onClick = { act("activate_lab") { st -> require(PolicyController(this@MainActivity).isOwner) { "Сначала provisioning Device Owner" }; Engine.activate(st) }; runCatching { PolicyController(this@MainActivity).enter(this@MainActivity) } }) { Text("Включить лабораторную защиту") }
            }
            if (s.activated && s.removal !in listOf(Removal.DEPROVISIONING, Removal.COMPLETE)) {
                OutlinedButton(onClick = {
                    runCatching {
                        val i = Intent("com.android.phone.EmergencyDialer.DIAL")
                        val resolved = requireNotNull(packageManager.resolveActivity(i, 0)) { "Экстренный dialer не обнаружен: требуется адаптер модели" }
                        require(PolicyController(this@MainActivity).dpm.isLockTaskPermitted(resolved.activityInfo.packageName))
                        i.component = android.content.ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                        startActivity(i, android.app.ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle())
                    }.onFailure { error = it.message ?: "Экстренный интерфейс недоступен" }
                }) { Text("Экстренный dialer") }
                if (s.mode == Mode.SESSION_ACTIVE) Button(onClick = { act("session_end", Engine::endSession) }) { Text("Завершить сеанс — остаток сгорит") }
                else if (s.pauseStarted == null) Button(onClick = { act("pause_start", Engine::beginPause) }) { Text("Ты точно уверен, что это необходимо?") }
                else {
                    Text("Пауза: ${maxOf(0, MINUTE - (now - s.pauseStarted!!)) / 1000} с")
                    Button(enabled = now - s.pauseStarted!! >= MINUTE, onClick = { act("session_start", Engine::startSession) }) { Text("Подтверждаю: открыть один сеанс") }
                    Button(onClick = { act("pause_cancel", Engine::cancelPause) }) { Text("Отменить без расходования") }
                }
                val controller = PolicyController(this@MainActivity)
                val permitted = remember(s, apps) { runCatching { controller.dpm.getLockTaskPackages(controller.admin).toSet() }.getOrDefault(emptySet()) }
                apps.filter { it.pkg in launchable && it.pkg in permitted && it.pkg != packageName }.forEach { app ->
                    OutlinedButton(onClick = { runCatching { controller.launch(this@MainActivity, app.pkg) }.onFailure { error = it.message ?: "Ошибка запуска" } }) { Text(app.label) }
                }
                Text("Процедура: ${s.removal}\nОсталось подтверждённого времени: ${maxOf(0, DELAY - (s.flow?.confirmedMs ?: 0)) / MINUTE} мин")
                if (s.removal == Removal.NONE) {
                    Button(onClick = { role = "main"; request("removal_start") }) { Text("Запросить начало удаления • 48 часов") }
                    Button(onClick = { runCatching { Protocol.strictJson(text); Protocol.json.decodeFromString<Policy>(text) }.onSuccess { role = "main"; request("policy_change", it) }.onFailure { error = it.message ?: "Неверная политика" } }) { Text("Запросить изменение политики из JSON • 48 часов") }
                }
                if (s.removal == Removal.READY_FOR_FINAL_CONFIRMATION) Button(onClick = { role = "main"; request(if (s.flow?.kind == "policy_change") "policy_change" else "removal_finish") }) { Text("Создать свежий финальный запрос") }
                if (s.flow != null) Button(onClick = { act("flow_cancel", Engine::cancelFlow) }) { Text("Отменить процедуру") }
                if (s.policy.emergencySpki.isNotEmpty()) Button(onClick = { request("emergency_release") }) { Text("Запрос доверенному человеку • аварийное снятие") }
            }
            if (s.removal == Removal.DEPROVISIONING) Button(onClick = { act("deprovision_retry") { it } }) { Text("Повторить незавершённый этап снятия") }
            if (s.removal == Removal.COMPLETE) {
                Text("DO/admin сняты и пакеты восстановлены в lab workflow. Проверьте личные данные и обычный HOME.")
                Button(onClick = { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))) }) { Text("Открыть системное подтверждение удаления") }
            }
            s.challenge?.takeIf { phoneSpki.isNotEmpty() }?.let { ch ->
                val envelope = buildJsonObject { put("envelopeVersion", 1); put("phoneSpki", phoneSpki); put("request", ch.compact) }.toString()
                Text("Запрос ${ch.message.requestId}\n${ch.message.messageType} • ${ch.message.phase}\nСрок: 15 минут в текущей загрузке")
                val bitmap = remember(envelope) { runCatching { BarcodeEncoder().encodeBitmap(envelope, BarcodeFormat.QR_CODE, 900, 900) }.getOrNull() }
                bitmap?.let { Image(it.asImageBitmap(), "Запрос для Windows", Modifier.fillMaxWidth().aspectRatio(1f)) }
                OutlinedTextField(envelope, {}, readOnly = true, label = { Text("Файл запроса") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { exportText = envelope; permitDocumentTask(); exporter.launch("focusguard-request.fgreq") }) { Text("Экспортировать запрос") }
            }
            OutlinedTextField(text, { if (it.length <= Protocol.MAX_INPUT) text = it }, label = { Text("Метаданные ключа / подписанный ответ / политика") }, modifier = Modifier.fillMaxWidth())
            Row {
                Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Сканируйте ключ или ответ Windows").setBeepEnabled(false)) }) { Text("Сканировать QR") }
                Button(onClick = { fileCallback = { text = it }; permitDocumentTask(); picker.launch(arrayOf("application/vnd.focusguard+jws", "text/plain", "application/json", "application/octet-stream")) }) { Text("Импорт файла") }
            }
            Button(onClick = { val response = text.trim(); act("signed_response") { Engine.applyResponse(it, response) }; text = "" }) { Text("Проверить и применить подписанный ответ") }
            if (s.activated) {
                BluetoothPanel()
                Text("Wi-Fi: WPA2-PSK, без открытия Settings. Другие типы Wi-Fi требуют модельного адаптера и испытаний.")
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") })
                OutlinedTextField(wifiPassword, { wifiPassword = it }, label = { Text("Пароль Wi-Fi") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(onClick = {
                    runCatching {
                        require(ssid.isNotBlank() && ssid.length <= 32 && wifiPassword.length in 8..63)
                        @Suppress("DEPRECATION") val cfg = WifiConfiguration().apply { SSID = "\"${ssid.replace("\"", "\\\"")}\""; preSharedKey = "\"${wifiPassword.replace("\"", "\\\"")}\"" }
                        val wifi = applicationContext.getSystemService(WifiManager::class.java)
                        @Suppress("DEPRECATION") val id = wifi.addNetwork(cfg)
                        @Suppress("DEPRECATION") check(id >= 0 && wifi.enableNetwork(id, true))
                    }.onFailure { error = "Wi-Fi: ${it.message}" }
                    wifiPassword = ""
                }) { Text("Подключить Wi-Fi") }
            }
        }
    }
}
