package ru.focusguard.android

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.app.KeyguardManager
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import ru.focusguard.core.*
import ru.focusguard.core.State

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf("")
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { incoming = Transfer.read(this, uri) }.onFailure { incoming = "Ошибка файла: ${it.message}" }
        runCatching { DocumentAccess.close(this) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        setContent { MaterialTheme { Surface { Screen() } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun accept(intent: Intent?) {
        val uri = intent?.data ?: intent?.let { androidx.core.content.IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) } ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) runCatching { incoming = Transfer.read(this, uri) }.onFailure { incoming = "Ошибка файла: ${it.message}" }
        else intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { if (it.toByteArray().size <= Protocol.MAX_INPUT) incoming = it.trim() }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { runCatching {
            val s = Storage(this@MainActivity).read()
            if (s.activated && s.daily != null) startForegroundService(Intent(this@MainActivity, BudgetService::class.java))
        } }
    }
    @Composable private fun Screen() {
        var state by remember { mutableStateOf<State?>(null) }
        var error by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var ack by remember { mutableStateOf(false) }
        var phraseAck by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var chosen by remember { mutableStateOf(setOf<String>()) }
        var limit by remember { mutableStateOf("120") }
        var apps by remember { mutableStateOf(listOf<Installed>()) }
        val scope = rememberCoroutineScope()
        fun work(block: suspend () -> State) {
            if (busy) return
            busy = true; error = ""
            scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { state = it }.onFailure { error = it.message ?: "Ошибка" }; busy = false }
        }
        fun change(event: String, f: (State) -> State) = work { GuardRuntime(this@MainActivity).update(event, f) }
        suspend fun sync(): State {
            val epoch = NetworkTime.now()
            return GuardRuntime(this@MainActivity).update("network_time") { s -> s.copy(daily = DailyEngine.sync(requireNotNull(s.daily), Storage(this@MainActivity).reading(), epoch)) }
        }
        fun request(action: String, signer: String = "", proposal: Policy? = null) = work {
            sync()
            GuardRuntime(this@MainActivity).update("request_$action") { s ->
                val d = requireNotNull(s.daily); val r = Storage(this@MainActivity).reading(); val now = requireNotNull(d.time(r))
                val identity = Identity()
                val key = signer.ifEmpty { if (action.startsWith("usb_")) s.policy.mainSpki else d.guardian }
                require(key.isNotEmpty()) { "Сначала настройте подтверждающее устройство" }
                if (action == "pair") require(!s.activated || d.replacementAllowed)
                if (action == "usb_pair") require(!s.activated)
                if (action.startsWith("usb_finish")) require(d.recoveryAt > 0 && now - d.recoveryAt >= DELAY) { "48 часов ещё не прошли" }
                val msg = Approval(action = action, phoneId = s.installationId, phoneSpki = identity.publicSpki, signerSpki = key,
                    day = d.day, issued = now, expires = now + 24 * 60 * MINUTE, policyVersion = s.policyVersion,
                    policy = proposal, replacement = if (action.startsWith("usb_finish")) d.recoveryAt.toString() else "")
                s.copy(daily = d.copy(pending = identity.sign(msg)))
            }
        }
        LaunchedEffect(incoming) { if (incoming.isNotEmpty()) { text = incoming; incoming = ""; phraseAck = false } }
        LaunchedEffect(state?.activated, state?.removal) {
            val current = state
            if (current?.activated == true && current.removal !in listOf(Removal.COMPLETE, Removal.DEPROVISIONING)) runCatching { PolicyController(this@MainActivity).enter(this@MainActivity) }
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val inv = Inventory(this@MainActivity)
                    apps = inv.all().filter { it.pkg in inv.launchable() && it.pkg !in inv.settingsPackages() && it.pkg != packageName }
                    val current = GuardRuntime(this@MainActivity).update("open") { s ->
                        // Never migrate an active legacy owner implicitly.
                        if (s.daily == null && !s.activated) s.copy(daily = DailyState(), policy = s.policy.copy(zone = "Europe/Moscow", emergencySpki = "")) else s
                    }
                    state = current; chosen = current.policy.apps.map { it.packageId }.toSet(); limit = ((current.daily?.limit ?: SESSION) / MINUTE).toString()
                }.onFailure { error = it.message ?: "Не удалось прочитать состояние" }
            }
            while (true) {
                delay(1000)
                if (!busy) runCatching { withContext(Dispatchers.IO) { Storage(this@MainActivity).read() } }.onSuccess { state = it }
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Focus Guard", style = MaterialTheme.typography.headlineLarge)
            Text("0.3 • тестовая версия • день начинается в 04:00 МСК")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            val s = state
            if (s == null) { Text("Загрузка…"); return@Column }
            val d = s.daily
            if (d == null) {
                Text("Обнаружено активное управление версии 0.1. Автоматическая миграция закрыта. Снимите прежнее управление с помощью Windows-программы 0.1 и прежнего USB-ключа.")
                Text("Снятие: ${s.removal}. Подтверждённое ожидание: ${(s.flow?.confirmedMs ?: 0) / MINUTE} из ${DELAY / MINUTE} мин. ${s.error}")
                fun legacyRequest(type: String, role: String = "main") = change("legacy_recovery") { st ->
                    val id = Identity(); Engine.challenge(st, type, id.keyId, { id.sign(it) }, role = role)
                }
                if (s.removal == Removal.NONE) Button(enabled = !busy, onClick = { legacyRequest("removal_start") }) { Text("Запросить начало прежнего снятия • 48 часов") }
                if (s.removal == Removal.READY_FOR_FINAL_CONFIRMATION) Button(enabled = !busy, onClick = { legacyRequest(if (s.flow?.kind == "policy_change") "policy_change" else "removal_finish") }) { Text("Создать прежний финальный запрос") }
                if (s.policy.emergencySpki.isNotEmpty() && s.removal !in listOf(Removal.COMPLETE, Removal.DEPROVISIONING)) Button(enabled = !busy, onClick = { legacyRequest("emergency_release", "emergency") }) { Text("Прежний аварийный запрос") }
                s.challenge?.let { ch ->
                    Button(onClick = {
                        exportContent = buildJsonObject { put("envelopeVersion", 1); put("phoneSpki", Identity().publicSpki); put("request", ch.compact) }.toString()
                        DocumentAccess.open(this@MainActivity); exporter.launch("focusguard-request.fgreq")
                    }) { Text("Сохранить запрос для Windows 0.1") }
                }
                Button(onClick = { DocumentAccess.open(this@MainActivity); picker.launch(arrayOf("*/*")) }) { Text("Открыть прежний подписанный ответ") }
                if (text.isNotEmpty()) Button(enabled = !busy, onClick = { change("legacy_response") { Engine.applyResponse(it, text.trim()) } }) { Text("Применить прежний ответ") }
                if (s.removal == Removal.DEPROVISIONING) Button(enabled = !busy, onClick = { change("legacy_retry") { it } }) { Text("Повторить снятие управления") }
                if (s.removal == Removal.COMPLETE) Button(onClick = { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }) { Text("Управление снято — удалить старую установку") }
                return@Column
            }
            Text(if (d.unlimited) "Сегодня без ограничения до 04:00 МСК" else "Осталось сегодня: ${d.remaining / MINUTE} мин")
            if (d.time(Storage(this@MainActivity).reading()) == null) Text("Нужно проверить время через интернет. После перезагрузки новые сутки не выдаются до проверки; сохранённый остаток доступен.")
            Button(enabled = !busy, onClick = { work { sync() } }) { Text("Проверить время через интернет") }
            if (!s.activated) {
                Text("Первоначальная настройка")
                Text("1. Выберите постоянные исключения. 2. Свяжите доверенный телефон. 3. Настройте флешку восстановления. 4. Разрешите учёт и проверьте выход на тестовом устройстве.")
                Button(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Разрешить учёт использования") }
                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Разрешить учёт фонового воспроизведения") }
                Button(onClick = { if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42) }) { Text("Разрешить уведомление об учёте") }
                Text("Учёт приложений: ${UsageMonitor.usageAllowed(this@MainActivity)}; воспроизведение: ${UsageMonitor.notificationAllowed(this@MainActivity)}")
            }
            Text("Доверенный человек: ${if (d.guardian.isEmpty()) "не подключён" else "подключён"}")
            Text("Флешка восстановления: ${if (s.policy.mainSpki.isEmpty()) "не настроена" else "настроена"}")
            Button(onClick = { showApps = !showApps }) { Text("Лимит и постоянные исключения") }
            if (showApps) {
                Text("Звонки, SMS и контакты разрешены автоматически. Отметьте WhatsApp и свои VPN: Incy, Happ, v2rayNG, v2RayTun. Другие отмеченные приложения тоже станут постоянными исключениями.")
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(4) }, label = { Text("Минут в день") })
                apps.forEach { app -> Row { Checkbox(app.pkg in chosen, { yes -> chosen = if (yes) chosen + app.pkg else chosen - app.pkg }); Text("${app.label}\n${app.pkg}") } }
                Button(enabled = !busy, onClick = {
                    runCatching {
                        val minutes = limit.toLong(); require(minutes in 1..1440)
                        require("com.whatsapp" in chosen) { "Выберите установленный WhatsApp — он нужен для связи" }
                        val p = s.policy.copy(sessionMs = minutes * MINUTE, apps = apps.filter { it.pkg in chosen }.map { it.pin() })
                        if (s.activated && (minutes * MINUTE > d.limit || !s.policy.apps.containsAll(p.apps))) request("policy", proposal = p)
                        else change("stricter_policy") { st -> st.copy(policy = p, policyVersion = st.policyVersion + 1, daily = st.daily!!.copy(limit = p.sessionMs, pending = "")) }
                    }.onFailure { error = it.message ?: "Неверные настройки" }
                }) { Text(if (s.activated) "Сохранить или запросить подтверждение" else "Сохранить настройки") }
            }
            if ((!s.activated && d.guardian.isEmpty()) || d.replacementAllowed) {
                Text("Импортируйте приглашение из приложения доверенного человека ниже.")
                val guardian = runCatching { Protocol.strictJson(text); val obj = Protocol.json.parseToJsonElement(text).jsonObject; require(obj["kind"]?.jsonPrimitive?.content == "guardian-invite"); obj.getValue("publicSpki").jsonPrimitive.content.also { Protocol.publicKey(it) } }.getOrNull()
                if (guardian != null) {
                    Text("Сверьте по звонку эту фразу: ${CompanionProtocol.phrase(Identity().publicSpki, guardian)}")
                    Row { Checkbox(phraseAck, { phraseAck = it }); Text("Сверю фразу с доверенным человеком по звонку") }
                    Button(enabled = phraseAck && !busy, onClick = { request("pair", guardian) }) { Text("Создать запрос привязки") }
                }
            }
            if (!s.activated) {
                val usb = runCatching { Protocol.strictJson(text); val obj = Protocol.json.parseToJsonElement(text).jsonObject; require(obj["role"]?.jsonPrimitive?.content == "main"); obj.getValue("publicSpki").jsonPrimitive.content.also { require(Protocol.keyId(it) == obj.getValue("keyId").jsonPrimitive.content) } }.getOrNull()
                if (usb != null) {
                    Text("Флешка: ${Protocol.keyId(usb)}. Сверьте с Windows.")
                    Button(enabled = !busy, onClick = { request("usb_pair", usb) }) { Text("Создать запрос регистрации флешки") }
                }
                Row { Checkbox(ack, { ack = it }); Text("Это тестовый телефон; снятие управления и сохранность данных проверены") }
                Button(enabled = ack && !busy && d.guardian.isNotEmpty() && s.policy.mainSpki.isNotEmpty(), onClick = {
                    change("activate") { st ->
                        require(BuildConfig.LAB_DEVICE) { "Только лабораторная сборка" }
                        require(PolicyController(this@MainActivity).isOwner) { "Сначала настройте Device Owner через ADB на тестовом телефоне" }
                        require(UsageMonitor.ready(this@MainActivity)) { "Разрешите учёт использования и фонового аудио" }
                        require(st.daily!!.time(Storage(this@MainActivity).reading()) != null) { "Проверьте сетевое время" }
                        require(st.daily!!.pending.isEmpty()) { "Завершите или отмените запрос" }
                        require(st.policy.apps.any { it.packageId == "com.whatsapp" }) { "Добавьте WhatsApp в исключения" }
                        Engine.validatePolicy(st.policy)
                        startForegroundService(Intent(this@MainActivity, BudgetService::class.java))
                        st.copy(activated = true)
                    }
                }) { Text("Включить защиту на тестовом телефоне") }
            } else if (s.removal !in listOf(Removal.DEPROVISIONING, Removal.COMPLETE)) {
                Button(enabled = !busy, onClick = { request("hour") }) { Text("Попросить ещё 1 час") }
                Button(enabled = !busy, onClick = { request("day") }) { Text("Попросить разблокировку до 04:00") }
                Button(enabled = !busy, onClick = { request("remove") }) { Text("Попросить разрешение на удаление") }
                apps.filter { app -> app.pkg in runCatching { PolicyController(this@MainActivity).dpm.getLockTaskPackages(PolicyController(this@MainActivity).admin).toSet() }.getOrDefault(emptySet()) }.forEach { app ->
                    OutlinedButton(onClick = { runCatching { PolicyController(this@MainActivity).launch(this@MainActivity, app.pkg) }.onFailure { error = it.message ?: "Не удалось открыть" } }) { Text(app.label) }
                }
                Text("Независимый выход через вашу флешку")
                if (d.recoveryAt == 0L) {
                    Button(enabled = !busy, onClick = { request("usb_start_replace") }) { Text("Заменить доверенного человека • 48 часов") }
                    Button(enabled = !busy, onClick = { request("usb_start_remove") }) { Text("Удалить Focus Guard • 48 часов") }
                } else {
                    val now = d.time(Storage(this@MainActivity).reading())
                    Text("Ожидание начато. Осталось: ${now?.let { maxOf(0, DELAY - (it - d.recoveryAt)) / MINUTE } ?: "проверьте время"} мин. Выключение телефона ожидание не останавливает.")
                    Button(enabled = !busy, onClick = { request(d.recoveryAction.replace("start", "finish")) }) { Text("Завершить через флешку") }
                }
                BluetoothPanel()
            }
            if (s.removal == Removal.DEPROVISIONING) {
                Text("Снятие управления: этап ${s.deprovisionStage}. ${s.error}")
                Button(onClick = { change("retry_remove") { it } }) { Text("Повторить снятие управления") }
            }
            if (s.removal == Removal.COMPLETE) {
                Text("Системное управление снято. Теперь можно удалить приложение.")
                Button(onClick = { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }) { Text("Удалить Focus Guard") }
            }
            if (d.pending.isNotEmpty()) {
                val msg = CompanionProtocol.inspect(d.pending)
                Text("Готов запрос: ${CompanionProtocol.label(msg)}. Для доверенного человека — WhatsApp; для флешки — файл на ПК.")
                Button(onClick = { Transfer.copy(this@MainActivity, CompanionProtocol.file(d.pending)); error = "Запрос скопирован. Отправьте текст в мессенджере." }) { Text("Скопировать запрос") }
                Button(enabled = !busy, onClick = { runCatching { Transfer.share(this@MainActivity, CompanionProtocol.file(d.pending)) }.onFailure { error = it.message ?: "Не удалось отправить" } }) { Text("Отправить запрос через WhatsApp") }
                Button(onClick = { exportContent = CompanionProtocol.file(d.pending); DocumentAccess.open(this@MainActivity); exporter.launch("FocusGuard-request.fg2.json") }) { Text("Сохранить запрос для ПК") }
                Button(onClick = { change("cancel_request") { it.copy(daily = it.daily!!.copy(pending = "")) } }) { Text("Отменить этот запрос") }
            }
            if (text.isNotEmpty()) Text("Получен файл. Приглашение или публичный ключ показываются в настройке выше. Для подписанного ответа нажмите «Применить подтверждение».")
            Button(onClick = { runCatching {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val code = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                require(code.isNotEmpty()) { "Сначала скопируйте код из мессенджера" }
                Protocol.strictJson(code); text = code; phraseAck = false
                error = "Код вставлен. Для приглашения продолжите настройку выше. Для ответа нажмите «Применить подтверждение»."
            }.onFailure { error = it.message ?: "Не удалось вставить код" } }) { Text("Вставить код") }
            Button(onClick = { DocumentAccess.open(this@MainActivity); picker.launch(arrayOf("*/*")) }) { Text("Открыть полученный файл") }
            Button(enabled = !busy && text.isNotEmpty(), onClick = { work {
                val token = CompanionProtocol.readFile(text)
                val req = CompanionProtocol.inspect(token)
                if (req.action.startsWith("usb_")) sync()
                GuardRuntime(this@MainActivity).update("apply_approval") { st -> DailyEngine.apply(st, token, Storage(this@MainActivity).reading()) }.also { text = "" }
            } }) { Text("Применить подтверждение") }
            Text("${s.error}\nТелефон: ${s.installationId}\nИдентичность: ${Identity().keyId}", style = MaterialTheme.typography.bodySmall)
        }
    }
    private var exportContent = ""
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(exportContent.toByteArray()) } }
        exportContent = ""
        runCatching { DocumentAccess.close(this) }
    }
}
