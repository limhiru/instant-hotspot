@file:Suppress("DEPRECATION", "MissingPermission")
package com.hiru.instanthotspot.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.hiru.instanthotspot.core.AuthSession
import com.hiru.instanthotspot.core.PairingProtocol as P
import com.hiru.instanthotspot.data.AppStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FoundDevice(val address: String, val name: String, val rssi: Int)
enum class LinkStage { IDLE, CONNECTING, AUTHENTICATING, REQUESTING, ACTION_REQUIRED }
data class BleState(
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val pairingUntil: Long = 0,
    val found: List<FoundDevice> = emptyList(),
    val stage: LinkStage = LinkStage.IDLE,
    val peerName: String = "",
    val incomingRequest: String? = null,
    val message: String? = null,
    val revision: Int = 0,
)

/** All GATT operations and callbacks are serialized on main; no scan polling loop. */
class BleEngine(private val context: Context, private val store: AppStore) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(BleState())
    val state = mutable.asStateFlow()
    private var key: ByteArray? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var clientGatt: BluetoothGatt? = null
    private var remoteId: UUID? = null
    private var challenge: ByteArray? = null
    private var step = ""
    private var pairingDeadline = 0L
    private var failures = 0
    private var cooldownUntil = 0L
    private var lastRequestAt = -30_000L
    private val sessions = mutableMapOf<String, AuthSession>()
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val stopScanTask = Runnable { stopScan() }
    private val timeoutTask = Runnable { failClient("연결 시간이 초과됐어요. 가까이에서 다시 시도해 주세요.") }
    private val closePairingTask = Runnable { pairingDeadline = 0; mutable.update { it.copy(pairingUntil = 0) } }
    var onIncomingRequest: ((String) -> Unit)? = null
    var onProviderFailure: (() -> Unit)? = null

    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun refresh() { mutable.update { it.copy(revision = it.revision + 1) } }
    private fun dispatch(block: () -> Unit) { main.post { runCatching(block).onFailure { message("Bluetooth 작업을 완료하지 못했어요. 권한과 연결 상태를 확인해 주세요.") } } }

    fun startProvider() {
        if (server != null) return
        key = store.key()
        if (key == null) { message("등록 코드를 먼저 설정해 주세요."); onProviderFailure?.invoke(); return }
        try {
            val bt = adapter
            if (bt?.isEnabled != true) { providerFailed("Bluetooth를 켜 주세요."); return }
            advertiser = bt.bluetoothLeAdvertiser
            if (advertiser == null) { providerFailed("이 기기는 BLE 광고를 지원하지 않아요. 인터넷 연결 역할로 사용해 주세요."); return }
            server = manager?.openGattServer(context, serverCallback)
            if (server == null) { providerFailed("BLE 요청 대기를 시작하지 못했어요."); return }
            val service = BluetoothGattService(P.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            listOf(P.CHALLENGE, P.STATUS).forEach { uuid ->
                service.addCharacteristic(BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            }
            service.addCharacteristic(BluetoothGattCharacteristic(P.IDENTITY,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE))
            service.addCharacteristic(BluetoothGattCharacteristic(P.CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            if (server?.addService(service) != true) providerFailed("BLE 서비스를 만들지 못했어요.")
        } catch (_: SecurityException) { providerFailed("근처 기기 권한을 허용해 주세요.") }
    }
    private fun providerFailed(text: String) { stopProvider(); message(text); onProviderFailure?.invoke() }
    fun stopProvider() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        advertiser = null; server = null; sessions.clear(); pairingDeadline = 0
        main.removeCallbacks(closePairingTask)
        mutable.update { it.copy(advertising = false, pairingUntil = 0, incomingRequest = null) }
    }
    fun allowPairing() {
        pairingDeadline = SystemClock.elapsedRealtime() + 120_000
        mutable.update { it.copy(pairingUntil = pairingDeadline, message = "2분 동안 새 기기를 등록할 수 있어요.") }
        main.removeCallbacks(closePairingTask)
        main.postDelayed(closePairingTask, 120_000)
    }
    fun clearRequest() { mutable.update { it.copy(incomingRequest = null) } }
    fun forgetPeer(id: String) {
        store.forget(id)
        // Disconnect authenticated sessions for the forgotten identity immediately.
        sessions.filterValues { it.client?.toString() == id }.keys.toList().forEach { address ->
            runCatching { adapter?.getRemoteDevice(address)?.let { server?.cancelConnection(it) } }
            sessions.remove(address)
        }
        disconnectClient(); refresh()
    }
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = dispatch {
            mutable.update { it.copy(advertising = true, message = null) }
        }
        override fun onStartFailure(errorCode: Int) = dispatch { providerFailed("BLE 광고를 시작하지 못했어요. 오류 $errorCode") }
    }
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            if (server == null) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) { providerFailed("BLE 서비스 등록에 실패했어요."); return@dispatch }
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).setTimeout(0).build()
            val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(P.SERVICE)).setIncludeDeviceName(false).build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (sessions.size >= 4 || SystemClock.elapsedRealtime() < cooldownUntil) server?.cancelConnection(device)
                else {
                    val session = AuthSession(SystemClock.elapsedRealtime())
                    sessions[device.address] = session
                    main.postDelayed({
                        if (sessions[device.address] === session) {
                            sessions.remove(device.address)
                            runCatching { server?.cancelConnection(device) }
                        }
                    }, 30_000)
                }
            } else sessions.remove(device.address)
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) = dispatch {
            val session = sessions[device.address]
            val now = SystemClock.elapsedRealtime()
            val secret = key
            val value = when {
                session == null || session.expired(now) || secret == null -> byteArrayOf(P.REJECTED)
                characteristic.uuid == P.IDENTITY -> P.idBytes(store.installationId)
                characteristic.uuid == P.CHALLENGE -> byteArrayOf(P.VERSION) + session.nonce
                characteristic.uuid == P.STATUS && session.authenticated && session.client != null -> {
                    val code = if (session.requestUsed) P.USER_ACTION_REQUIRED else P.AUTHENTICATED
                    byteArrayOf(code) + P.tag(secret, "server/$code", session.nonce, store.installationId, session.client!!)
                }
                else -> byteArrayOf(P.REJECTED)
            }
            if (offset < 0 || offset > value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = dispatch {
            val session = sessions[device.address]
            val now = SystemClock.elapsedRealtime()
            val secret = key
            var accepted = false
            var incoming: String? = null
            if (!preparedWrite && offset == 0 && session != null && !session.expired(now) && secret != null && now >= cooldownUntil) {
                if (characteristic.uuid == P.IDENTITY && value.size == 16 && !session.authenticated) {
                    session.client = P.idFrom(value); accepted = true
                } else if (characteristic.uuid == P.CONTROL && value.size == 17) {
                    val peer = session.client
                    when (value[0]) {
                        P.AUTH -> if (peer != null && (store.known(peer) || now < pairingDeadline)) {
                            accepted = session.authenticate(secret, store.installationId, value.copyOfRange(1, 17), now)
                            if (accepted) {
                                failures = 0
                                store.remember(peer, "등록 기기 · ${peer.toString().take(6)}"); refresh()
                            } else authenticationFailed(now)
                        }
                        P.REQUEST -> if (now - lastRequestAt >= 10_000) {
                            accepted = session.acceptRequest(secret, store.installationId, value.copyOfRange(1, 17), now)
                            if (accepted) {
                                lastRequestAt = now
                                incoming = store.peers().find { it.id == peer.toString() }?.name ?: "등록된 기기"
                            }
                        }
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            incoming?.let { name ->
                mutable.update { it.copy(incomingRequest = name) }
                onIncomingRequest?.invoke(name)
            }
        }
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
    }
    private fun authenticationFailed(now: Long) {
        failures++
        if (failures >= 5) { cooldownUntil = now + 30_000; failures = 0 }
    }

    fun scan() {
        if (mutable.value.scanning || clientGatt != null) return
        try {
            val bt = adapter
            if (bt?.isEnabled != true) { message("Bluetooth를 켜 주세요."); return }
            scanner = bt.bluetoothLeScanner
            if (scanner == null) { message("BLE 검색을 사용할 수 없어요."); return }
            devices.clear()
            mutable.update { it.copy(scanning = true, found = emptyList(), message = null) }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(P.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
            main.postDelayed(stopScanTask, 10_000)
        } catch (_: SecurityException) { stopScan(); message("Bluetooth와 위치 권한을 확인해 주세요.") }
    }
    fun stopScan() {
        main.removeCallbacks(stopScanTask)
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        mutable.update { it.copy(scanning = false) }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = dispatch {
            if (!mutable.value.scanning) return@dispatch
            devices[result.device.address] = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "Hiru 폰 · ${result.device.address.takeLast(5)}"
            val found = FoundDevice(result.device.address, name, result.rssi)
            mutable.update { old -> old.copy(found = (old.found.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi }.take(20)) }
        }
        override fun onScanFailed(errorCode: Int) = dispatch { stopScan(); message("검색을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요. 오류 $errorCode") }
    }
    fun connectAndRequest(address: String) {
        val device = devices[address] ?: run { message("기기를 다시 검색해 주세요."); return }
        stopScan(); disconnectClient()
        key = store.key()
        if (key == null) { message("등록 코드를 먼저 설정해 주세요."); return }
        remoteId = null; challenge = null; step = "connect"
        val name = mutable.value.found.find { it.address == address }?.name ?: "Hiru 폰"
        mutable.update { it.copy(stage = LinkStage.CONNECTING, peerName = name, message = null) }
        main.postDelayed(timeoutTask, 25_000)
        try {
            clientGatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            if (clientGatt == null) failClient("BLE 연결을 시작하지 못했어요.")
        } catch (_: SecurityException) { failClient("근처 기기 권한을 허용해 주세요.") }
    }
    fun disconnectClient() {
        main.removeCallbacks(timeoutTask)
        val old = clientGatt
        clientGatt = null
        runCatching { old?.disconnect() }; runCatching { old?.close() }
        remoteId = null; challenge = null; step = ""
        mutable.update { it.copy(stage = LinkStage.IDLE) }
    }
    private fun failClient(text: String) { disconnectClient(); message(text) }
    private fun read(gatt: BluetoothGatt, uuid: UUID, nextStep: String) {
        val characteristic = gatt.getService(P.SERVICE)?.getCharacteristic(uuid)
        step = nextStep
        if (characteristic == null || !gatt.readCharacteristic(characteristic)) failClient("호환되는 Hiru 서비스가 없어요.")
    }
    private fun write(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, nextStep: String) {
        val characteristic = gatt.getService(P.SERVICE)?.getCharacteristic(uuid)
        if (characteristic == null) { failClient("호환되는 Hiru 서비스가 없어요."); return }
        step = nextStep
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) failClient("요청을 전송하지 못했어요.")
    }
    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch {
            if (gatt !== clientGatt) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) { failClient("기기 연결이 끊겼어요. 가까이에서 다시 시도해 주세요."); return@dispatch }
            if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices()) failClient("서비스를 확인하지 못했어요.")
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch {
            if (gatt !== clientGatt) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) { failClient("기기 서비스를 확인하지 못했어요."); return@dispatch }
            mutable.update { it.copy(stage = LinkStage.AUTHENTICATING) }
            read(gatt, P.IDENTITY, "server-id")
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(gatt, characteristic.uuid, characteristic.value?.clone() ?: byteArrayOf(), status)
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(gatt, characteristic.uuid, value.clone(), status)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch {
            if (gatt !== clientGatt) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) { failClient("인증 또는 요청이 거절됐어요. 같은 코드인지, 폰의 기기 등록이 열려 있는지 확인해 주세요."); return@dispatch }
            val nonce = challenge ?: return@dispatch
            val serverId = remoteId ?: return@dispatch
            val secret = key ?: return@dispatch
            when (step) {
                "client-id" -> write(gatt, P.CONTROL, byteArrayOf(P.AUTH) + P.tag(secret, "client", nonce, serverId, store.installationId), "auth")
                "auth" -> read(gatt, P.STATUS, "verify")
                "request" -> read(gatt, P.STATUS, "result")
            }
        }
    }
    private fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) = dispatch {
        if (gatt !== clientGatt) return@dispatch
        if (status != BluetoothGatt.GATT_SUCCESS) { failClient("기기 응답을 받지 못했어요."); return@dispatch }
        when (step) {
            "server-id" -> {
                if (uuid != P.IDENTITY || value.size != 16) { failClient("호환되지 않는 기기예요."); return@dispatch }
                remoteId = P.idFrom(value)
                read(gatt, P.CHALLENGE, "challenge")
            }
            "challenge" -> {
                if (uuid != P.CHALLENGE || value.size != 17 || value[0] != P.VERSION) { failClient("호환되지 않는 인증 방식이에요."); return@dispatch }
                challenge = value.copyOfRange(1, 17)
                write(gatt, P.IDENTITY, P.idBytes(store.installationId), "client-id")
            }
            "verify", "result" -> {
                val nonce = challenge ?: return@dispatch
                val serverId = remoteId ?: return@dispatch
                val secret = key ?: return@dispatch
                val code = if (step == "verify") P.AUTHENTICATED else P.USER_ACTION_REQUIRED
                if (uuid != P.STATUS || value.size != 17 || value[0] != code ||
                    !P.matches(P.tag(secret, "server/$code", nonce, serverId, store.installationId), value.copyOfRange(1, 17))) {
                    failClient("폰 인증을 확인하지 못했어요. 등록 코드를 확인해 주세요."); return@dispatch
                }
                if (step == "verify") {
                    store.remember(serverId, mutable.value.peerName); refresh()
                    mutable.update { it.copy(stage = LinkStage.REQUESTING) }
                    write(gatt, P.CONTROL, byteArrayOf(P.REQUEST) + P.tag(secret, "request", nonce, serverId, store.installationId), "request")
                } else {
                    disconnectClient()
                    mutable.update { it.copy(stage = LinkStage.ACTION_REQUIRED, message = null) }
                }
            }
        }
    }
}
