package net.mvexel.intervalswatch.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The power meter the user picked, remembered across workouts. */
class PowerMeterStore(context: Context) {
    private val prefs = context.getSharedPreferences("power_meter", Context.MODE_PRIVATE)
    val address: String? get() = prefs.getString("address", null)
    val name: String? get() = prefs.getString("name", null)

    fun save(address: String, name: String?) {
        prefs.edit().putString("address", address).putString("name", name).apply()
    }

    fun forget() = prefs.edit().clear().apply()
}

data class FoundPowerMeter(val address: String, val name: String)

/** Scans for devices advertising the Cycling Power Service for the picker screen. */
@SuppressLint("MissingPermission") // Caller checks BLUETOOTH_SCAN / BLUETOOTH_CONNECT.
class PowerMeterScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<FoundPowerMeter>>(emptyList())
    val devices: StateFlow<List<FoundPowerMeter>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _devices.value = emptyList()
        _scanning.value = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(PowerMeter.CPS_SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().build(), callback)
        handler.postDelayed(::stop, PowerMeter.SCAN_TIMEOUT_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (_scanning.value) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (_devices.value.any { it.address == address }) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: address
            _devices.value = _devices.value + FoundPowerMeter(address, name)
        }
    }
}

/**
 * Minimal BLE Cycling Power Service (0x1818) client. Bike power meters and Stryd (running power)
 * both expose it. Connects to the device remembered in [PowerMeterStore] (picked on the watch),
 * reports 3 s average power. Without a remembered device, or if it doesn't show up within
 * [SCAN_TIMEOUT_MS], the status becomes NOT_FOUND.
 */
@SuppressLint("MissingPermission") // Caller checks BLUETOOTH_SCAN / BLUETOOTH_CONNECT.
class PowerMeter(private val context: Context) {

    enum class Status { IDLE, SCANNING, CONNECTED, DISCONNECTED, NOT_FOUND }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _watts = MutableStateFlow<Double?>(null)
    val watts: StateFlow<Double?> = _watts.asStateFlow()
    var deviceName: String? = null
        private set

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val store = PowerMeterStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun start() {
        val device = store.address?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }
        if (device == null) {
            _status.value = Status.NOT_FOUND
            return
        }
        _status.value = Status.SCANNING
        deviceName = store.name
        // autoConnect=true keeps trying in the background, so a late-appearing sensor still connects.
        gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed({
            if (_status.value == Status.SCANNING) _status.value = Status.NOT_FOUND
        }, SCAN_TIMEOUT_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        _status.value = Status.IDLE
        _watts.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _status.value = Status.DISCONNECTED
                    _watts.value = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(CPS_SERVICE)?.getCharacteristic(CPS_MEASUREMENT) ?: return
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD)?.let {
                g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            _status.value = Status.CONNECTED
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != CPS_MEASUREMENT || value.size < 4) return
            // Bytes 0-1: flags. Bytes 2-3: instantaneous power, sint16 little endian.
            val p = ((value[3].toInt() shl 8) or (value[2].toInt() and 0xff)).toShort().toInt()
            val now = SystemClock.elapsedRealtime()
            samples.addLast(now to p)
            while (samples.isNotEmpty() && now - samples.first().first > 3000) samples.removeFirst()
            _watts.value = samples.map { it.second }.average()
        }
    }

    companion object {
        val CPS_SERVICE: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        val CPS_MEASUREMENT: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 20_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
