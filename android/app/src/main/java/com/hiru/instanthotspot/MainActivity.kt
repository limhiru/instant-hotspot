package com.hiru.instanthotspot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.hiru.instanthotspot.ble.ProviderService
import com.hiru.instanthotspot.ble.RequestNotifications
import com.hiru.instanthotspot.data.DeviceRole
import com.hiru.instanthotspot.ui.HiruApp

class MainActivity : ComponentActivity() {
    val app get() = application as HotspotApplication
    val wifiInternet = mutableStateOf(false)
    private var pendingAction: (() -> Unit)? = null
    private var pendingPermissions = emptyArray<String>()
    private var pendingScan = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingPermissions.all(::hasPermission)) continueAction()
        else { pendingAction = null; app.ble.message("기기를 연결하려면 요청한 Bluetooth·위치 권한을 허용해 주세요.") }
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) continueAction()
        else { pendingAction = null; app.ble.message("Bluetooth를 켠 후 다시 시도해 주세요.") }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HiruApp(this) }
    }
    override fun onResume() {
        super.onResume()
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        wifiInternet.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    override fun onStop() { app.ble.stopScan(); super.onStop() }
    private fun hasPermission(name: String) = ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
    private fun withBluetooth(scan: Boolean, action: () -> Unit) {
        pendingAction = action; pendingScan = scan
        pendingPermissions = if (Build.VERSION.SDK_INT >= 31) {
            if (scan) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else if (scan) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()
        val missing = pendingPermissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else continueAction()
    }
    private fun continueAction() {
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null) { pendingAction = null; app.ble.message("이 기기에서 Bluetooth를 사용할 수 없어요."); return }
        if (!bt.isEnabled) { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        if (pendingScan && Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(LocationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true
                else lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!enabled) {
                pendingAction = null
                app.ble.message("이 Android 버전에서는 BLE 검색을 위해 위치 서비스를 켜야 해요.")
                openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                return
            }
        }
        val action = pendingAction; pendingAction = null
        action?.invoke()
    }
    fun startProvider(pairing: Boolean = false) = withBluetooth(false) {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ProviderService::class.java).putExtra("pairing", pairing))
        }.onFailure { app.ble.message("대기를 시작하지 못했어요. 앱에서 다시 시도해 주세요.") }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun stopProvider() {
        stopService(Intent(this, ProviderService::class.java))
        app.ble.stopProvider()
    }
    fun scan() = withBluetooth(true) { app.ble.scan() }
    fun request(address: String) = withBluetooth(true) { app.ble.connectAndRequest(address) }
    fun openHotspotSettings() = openSettings("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
    fun openWifiSettings() = openSettings(Settings.ACTION_WIFI_SETTINGS)
    fun copyRoutineKeyword() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("루틴 키워드", RequestNotifications.ROUTINE_KEYWORD))
        app.ble.message("루틴 키워드를 복사했어요.")
    }
    fun openRequestNotificationSettings() {
        RequestNotifications.createChannel(this)
        runCatching {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, RequestNotifications.CHANNEL))
        }.onFailure { openSettings(Settings.ACTION_SETTINGS) }
    }
    fun testRoutine() {
        if (app.store.role != DeviceRole.PROVIDER || !app.store.routineEnabled) return
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            app.ble.message("알림을 허용한 뒤 테스트를 다시 눌러 주세요.")
            return
        }
        val posted = RequestNotifications.post(this, "루틴 연결 테스트", routine = true)
        app.ble.message(if (posted) "테스트 알림을 보냈어요. 폰의 핫스팟이 켜졌는지 확인해 주세요."
            else "요청 알림이 차단돼 있어요. '요청 알림 설정'에서 허용해 주세요.")
    }
    private fun openSettings(action: String, fallback: String = Settings.ACTION_SETTINGS) {
        runCatching { startActivity(Intent(action)) }.onFailure { runCatching { startActivity(Intent(fallback)) } }
    }
}
