package ru.focusguard.android

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Small DO-only interface; never exposes full Settings. Needs OEM validation. */
@Suppress("MissingPermission", "DEPRECATION")
@Composable fun BluetoothPanel() {
    val ctx = LocalContext.current
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var status by remember { mutableStateOf("") }
    var permitted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> permitted = granted.values.all { it }; status = if (permitted) "Разрешено" else "Разрешение Bluetooth не выдано" }
    DisposableEffect(adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (permitted && device != null && devices.none { it.address == device.address }) devices = devices + device
            }
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        onDispose { ctx.unregisterReceiver(receiver); if (permitted) runCatching { adapter?.cancelDiscovery() } }
    }
    Column {
        Text("Bluetooth • ограниченный интерфейс")
        if (adapter == null) Text("Bluetooth отсутствует")
        else if (!permitted) Button(onClick = { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }) { Text("Разрешить Bluetooth") }
        else {
            Button(onClick = { runCatching { check(adapter.enable()); status = "Включение Bluetooth (DO)" }.onFailure { status = "ОС отказала: ${it.message}" } }) { Text("Включить Bluetooth") }
            Button(onClick = { runCatching { check(adapter.disable()); status = "Выключение Bluetooth" }.onFailure { status = "ОС отказала: ${it.message}" } }) { Text("Выключить Bluetooth") }
            Button(onClick = { runCatching { devices = adapter.bondedDevices.toList(); check(adapter.startDiscovery()); status = "Поиск устройств" }.onFailure { status = "Поиск: ${it.message}" } }) { Text("Показать сопряжённые и искать рядом") }
            devices.forEach { device ->
                OutlinedButton(onClick = { runCatching { adapter.cancelDiscovery(); check(device.createBond()); status = "Запрошено системное подтверждение сопряжения" }.onFailure { status = "Сопряжение: ${it.message}" } }) { Text("${device.name ?: "Без имени"} • ${device.address}") }
            }
        }
        Text(status)
    }
}
