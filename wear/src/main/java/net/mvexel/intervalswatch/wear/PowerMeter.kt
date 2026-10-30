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
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Minimal BLE Cycling Power Service (0x1818) client. Bike power meters and Stryd (running power)
 * both expose it. Connects to the first device found, reports 3 s average power.
 */
@SuppressLint("MissingPermission") // Caller checks BLUETOOTH_SCAN / BLUETOOTH_CONNECT.
class PowerMeter(private val context: Context) {

    enum class Status { IDLE, SCANNING, CONNECTED, DISCONNECTED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _watts = MutableStateFlow<Double?>(null)
    val watts: StateFlow<Double?> = _watts.asStateFlow()
    var deviceName: String? = null
        private set

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _status.value = Status.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CPS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        gatt?.close()
        gatt = null
        _status.value = Status.IDLE
        _watts.value = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            connect(result.device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        deviceName = device.name
        gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
