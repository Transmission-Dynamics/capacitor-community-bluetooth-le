package com.capacitorjs.community.plugins.bluetoothle

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.getcapacitor.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class CallbackResponse(
    val success: Boolean,
    val value: String,
)

class TimeoutHandler(
    val key: String,
    val handler: Handler
)

fun <T> ConcurrentLinkedQueue<T>.popFirstMatch(predicate: (T) -> Boolean): T? {
    synchronized(this) {
        val iterator = this.iterator()
        while (iterator.hasNext()) {
            val nextItem = iterator.next()
            if (predicate(nextItem)) {
                iterator.remove()
                return nextItem
            }
        }
        return null
    }
}

class Device(
    private val context: Context,
    bluetoothAdapter: BluetoothAdapter,
    private val address: String,
    private val onDisconnect: () -> Unit
) {
    companion object {
        private val TAG = Device::class.java.simpleName
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
        private const val REQUEST_MTU = 512
    }

    private var connectionState = STATE_DISCONNECTED
    private var device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
    private var bluetoothGatt: BluetoothGatt? = null
    private var callbackMap = HashMap<String, ((CallbackResponse) -> Unit)>()
    private val timeoutQueue = ConcurrentLinkedQueue<TimeoutHandler>()
    private var bondStateReceiver: BroadcastReceiver? = null
    private var currentMtu = -1

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt, status: Int, newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED
                // service discovery is required to use services
                Logger.debug(TAG, "Connected to GATT server. Starting service discovery.")
                val result = bluetoothGatt?.discoverServices()
                if (result != true) {
                    reject("connect", "Starting service discovery failed.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                onDisconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                Logger.debug(TAG, "Disconnected from GATT server.")
                resolve("disconnect", "Disconnected.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve("discoverServices", "Services discovered.")
                if (connectCallOngoing()) {
                    // Try requesting a larger MTU. Maximally supported MTU will be selected.
                    requestMtu(REQUEST_MTU)
                }
            } else {
                reject("discoverServices", "Service discovery failed.")
                reject("connect", "Service discovery failed.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Logger.debug(TAG, "MTU changed: $mtu")
            } else {
                Logger.debug(TAG, "MTU change failed: $mtu")
            }
            resolve("connect", "Connected.")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
            val key = "readRssi"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve(key, rssi.toString())
            } else {
                reject(key, "Reading RSSI failed.")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            val key = "read|${characteristic.service.uuid}|${characteristic.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                if (data != null) {
                    val value = bytesToString(data)
                    resolve(key, value)
                } else {
                    reject(key, "No data received while reading characteristic.")
                }
            } else {
                reject(key, "Reading characteristic failed.")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val key = "write|${characteristic.service.uuid}|${characteristic.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve(key, "Characteristic successfully written.")
            } else {
                reject(key, "Writing characteristic failed.")
            }

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val notifyKey = "notification|${characteristic.service.uuid}|${characteristic.uuid}"
            val data = characteristic.value
            if (data != null) {
                val value = bytesToString(data)
                callbackMap[notifyKey]?.invoke(CallbackResponse(true, value))
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            val key =
                "readDescriptor|${descriptor.characteristic.service.uuid}|${descriptor.characteristic.uuid}|${descriptor.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = descriptor.value
                if (data != null) {
                    val value = bytesToString(data)
                    resolve(key, value)
                } else {
                    reject(key, "No data received while reading descriptor.")
                }
            } else {
                reject(key, "Reading descriptor failed.")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val key =
                "writeDescriptor|${descriptor.characteristic.service.uuid}|${descriptor.characteristic.uuid}|${descriptor.uuid}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resolve(key, "Descriptor successfully written.")
            } else {
                reject(key, "Writing descriptor failed.")
            }
        }
    }

    fun getId(): String {
        return address
    }

    /**
     * Actions that will be executed (see gattCallback)
     * - connect to gatt server
     * - discover services
     * - request MTU
     */
    fun connect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "connect"
        callbackMap[key] = callback
        if (isConnected()) {
            resolve(key, "Already connected.")
            return
        }
        bluetoothGatt?.close()
        connectionState = STATE_CONNECTING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        } else {
            bluetoothGatt = device.connectGatt(
                context, false, gattCallback
            )
        }
        setConnectionTimeout(key, "Connection timeout.", bluetoothGatt, timeout)
    }

    private fun connectCallOngoing(): Boolean {
        return callbackMap.containsKey("connect")
    }

    fun isConnected(): Boolean {
        return bluetoothGatt != null && connectionState == STATE_CONNECTED
    }

    private fun requestMtu(mtu: Int) {
        Logger.debug(TAG, "requestMtu $mtu")
        val result = bluetoothGatt?.requestMtu(mtu)
        if (result != true) {
            reject("connect", "Starting requestMtu failed.")
        }
    }

    fun getMtu(): Int {
        return currentMtu
    }

    fun requestConnectionPriority(connectionPriority: Int): Boolean {
        return bluetoothGatt?.requestConnectionPriority(connectionPriority) ?: false
    }

    fun createBond(callback: (CallbackResponse) -> Unit) {
        val key = "createBond"
        callbackMap[key] = callback
        try {
            createBondStateReceiver()
        } catch (e: Error) {
            Logger.error(TAG, "Error while registering bondStateReceiver: ${e.localizedMessage}", e)
            reject(key, "Creating bond failed.")
            return
        }
        val result = device.createBond()
        if (!result) {
            reject(key, "Creating bond failed.")
            return
        }
        // if already bonded, resolve immediately
        if (isBonded()) {
            resolve(key, "Creating bond succeeded.")
            return
        }
        // otherwise, wait for bond state change
    }

    private fun createBondStateReceiver() {
        if (bondStateReceiver == null) {
            bondStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        val key = "createBond"
                        val updatedDevice =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        // BroadcastReceiver receives bond state updates from all devices, need to filter by device
                        if (device.address == updatedDevice?.address) {
                            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                            val previousBondState =
                                intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            Logger.debug(
                                TAG, "Bond state transition $previousBondState -> $bondState"
                            )
                            if (bondState == BluetoothDevice.BOND_BONDED) {
                                resolve(key, "Creating bond succeeded.")
                            } else if (previousBondState == BluetoothDevice.BOND_BONDING && bondState == BluetoothDevice.BOND_NONE) {
                                reject(key, "Creating bond failed.")
                            } else if (bondState == -1) {
                                reject(key, "Creating bond failed.")
                            }
                        }
                    }
                }
            }
            val intentFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondStateReceiver, intentFilter)
        }
    }

    fun isBonded(): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    fun disconnect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "disconnect"
        callbackMap[key] = callback
        if (bluetoothGatt == null) {
            resolve(key, "Disconnected.")
            return
        }
        bluetoothGatt?.disconnect()
        setTimeout(key, "Disconnection timeout.", timeout)
    }

    fun getServices(): MutableList<BluetoothGattService> {
        return bluetoothGatt?.services ?: mutableListOf()
    }

    fun discoverServices(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "discoverServices"
        callbackMap[key] = callback
        refreshDeviceCache()
        val result = bluetoothGatt?.discoverServices()
        if (result != true) {
            reject(key, "Service discovery failed.")
            return
        }
        setTimeout(key, "Service discovery timeout.", timeout)
    }

    private fun refreshDeviceCache(): Boolean {
        var result = false

        try {
            if (bluetoothGatt != null) {
                val refresh = bluetoothGatt!!.javaClass.getMethod("refresh")
                result = (refresh.invoke(bluetoothGatt) as Boolean)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error while refreshing device cache: ${e.localizedMessage}", e)
        }

        Logger.debug(TAG, "Device cache refresh $result")
        return result
    }

    fun readRssi(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "readRssi"
        callbackMap[key] = callback
        val result = bluetoothGatt?.readRemoteRssi()
        if (result != true) {
            reject(key, "Reading RSSI failed.")
            return
        }
        setTimeout(key, "Reading RSSI timeout.", timeout)
    }

    fun read(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "read|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val result = bluetoothGatt?.readCharacteristic(characteristic)
        if (result != true) {
            reject(key, "Reading characteristic failed.")
            return
        }
        setTimeout(key, "Read timeout.", timeout)
    }

    fun write(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        value: String,
        writeType: Int,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "write|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val bytes = stringToBytes(value)
        characteristic.value = bytes
        characteristic.writeType = writeType
        val result = bluetoothGatt?.writeCharacteristic(characteristic)
        if (result != true) {
            reject(key, "Writing characteristic failed.")
            return
        }
        setTimeout(key, "Write timeout.", timeout)
    }

    fun setNotifications(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        enable: Boolean,
        notifyCallback: ((CallbackResponse) -> Unit)?,
        callback: (CallbackResponse) -> Unit,
    ) {
        val key = "writeDescriptor|$serviceUUID|$characteristicUUID|$CLIENT_CHARACTERISTIC_CONFIG"
        val notifyKey = "notification|$serviceUUID|$characteristicUUID"
        callbackMap[key] = callback
        if (notifyCallback != null) {
            callbackMap[notifyKey] = notifyCallback
        }
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }

        val result = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        if (result != true) {
            reject(key, "Setting notification failed.")
            return
        }

        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
        if (descriptor == null) {
            reject(key, "Setting notification failed.")
            return
        }

        if (enable) {
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }

        val resultDesc = bluetoothGatt?.writeDescriptor(descriptor)
        if (resultDesc != true) {
            reject(key, "Setting notification failed.")
            return
        }
        // wait for onDescriptorWrite
    }

    fun readDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "readDescriptor|$serviceUUID|$characteristicUUID|$descriptorUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        if (descriptor == null) {
            reject(key, "Descriptor not found.")
            return
        }
        val result = bluetoothGatt?.readDescriptor(descriptor)
        if (result != true) {
            reject(key, "Reading descriptor failed.")
            return
        }
        setTimeout(key, "Read descriptor timeout.", timeout)
    }

    fun writeDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        value: String,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "writeDescriptor|$serviceUUID|$characteristicUUID|$descriptorUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        if (descriptor == null) {
            reject(key, "Descriptor not found.")
            return
        }
        val bytes = stringToBytes(value)
        descriptor.value = bytes
        val result = bluetoothGatt?.writeDescriptor(descriptor)
        if (result != true) {
            reject(key, "Writing characteristic failed.")
            return
        }
        setTimeout(key, "Write timeout.", timeout)
    }

    private fun resolve(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Logger.debug(TAG, "resolve: $key $value")
            timeoutQueue.popFirstMatch { it.key == key }?.handler?.removeCallbacksAndMessages(null)
            callbackMap[key]?.invoke(CallbackResponse(true, value))
            callbackMap.remove(key)
        }
    }

    private fun reject(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Logger.debug(TAG, "reject: $key $value")
            timeoutQueue.popFirstMatch { it.key == key }?.handler?.removeCallbacksAndMessages(null)
            callbackMap[key]?.invoke(CallbackResponse(false, value))
            callbackMap.remove(key)
        }
    }

    private fun setTimeout(
        key: String, message: String, timeout: Long
    ) {
        val handler = Handler(Looper.getMainLooper())
        timeoutQueue.add(TimeoutHandler(key, handler))
        handler.postDelayed({
            reject(key, message)
        }, timeout)
    }

    private fun setConnectionTimeout(
        key: String,
        message: String,
        gatt: BluetoothGatt?,
        timeout: Long,
    ) {
        val handler = Handler(Looper.getMainLooper())
        timeoutQueue.add(TimeoutHandler(key, handler))
        handler.postDelayed({
            connectionState = STATE_DISCONNECTED
            gatt?.disconnect()
            gatt?.close()
            reject(key, message)
        }, timeout)
    }
}