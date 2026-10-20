package com.eve.robot

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.UUID

enum class EveBaseLinkState {
    DISCONNECTED,
    WAITING_FOR_BLUETOOTH,
    SCANNING,
    CONNECTING,
    CONFIGURING,
    READY,
    ERROR
}

/**
 * Optional BLE adapter for the published EVE base GATT contract.
 *
 * This class will only connect to a base advertising EveRobotBaseProtocol.SERVICE_UUID_TEXT.
 * It does not assume that arbitrary Bluetooth toys share the same motor protocol.
 */
class EveBleRobotBase(
    context: Context,
    private val listener: Listener
) : Closeable {
    interface Listener {
        fun onStateChanged(state: EveBaseLinkState, detail: String)
        fun onTelemetry(telemetry: EveRobotBaseProtocol.Telemetry)
        fun onPose(pose: EveRobotBaseProtocol.Pose)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val serviceUuid = UUID.fromString(EveRobotBaseProtocol.SERVICE_UUID_TEXT)
    private val commandUuid = UUID.fromString(EveRobotBaseProtocol.COMMAND_UUID_TEXT)
    private val telemetryUuid = UUID.fromString(EveRobotBaseProtocol.TELEMETRY_UUID_TEXT)
    private val poseUuid = UUID.fromString(EveRobotBaseProtocol.POSE_UUID_TEXT)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @Volatile
    var state: EveBaseLinkState = EveBaseLinkState.DISCONNECTED
        private set

    @Volatile
    var latestTelemetry: EveRobotBaseProtocol.Telemetry? = null
        private set

    @Volatile
    var latestPose: EveRobotBaseProtocol.Pose? = null
        private set

    @Volatile
    var lastDetail: String = "لم يتم توصيل قاعدة متوافقة."
        private set

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var commandWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var poseCharacteristic: BluetoothGattCharacteristic? = null
    private var sequence = 0
    private val telemetrySequenceGuard = EveBaseFrameSequenceGuard()
    private val poseSequenceGuard = EveBaseFrameSequenceGuard()
    private val writeLock = Any()
    private var writeInFlight = false
    private var pendingFrame: ByteArray? = null
    private var closed = false

    private val scanTimeout = Runnable {
        stopScanningInternal()
        if (state == EveBaseLinkState.SCANNING) {
            update(EveBaseLinkState.DISCONNECTED, "لم أعثر على قاعدة إيف المتوافقة خلال مهلة البحث.")
        }
    }

    @Synchronized
    fun startScan(): Boolean {
        if (closed) return fail("أعيد تشغيل التطبيق لإعادة تهيئة Bluetooth.")
        if (!hasRequiredPermissions()) return fail("إذن Bluetooth غير ممنوح.")
        val currentAdapter = adapter ?: return fail("هذا الهاتف لا يوفّر Bluetooth LE.")
        if (!currentAdapter.isEnabled) {
            update(EveBaseLinkState.WAITING_FOR_BLUETOOTH, "Bluetooth متوقف؛ فعّله ثم أعد طلب الاتصال.")
            return false
        }
        val scanner = currentAdapter.bluetoothLeScanner
            ?: return fail("ماسح Bluetooth LE غير متاح الآن.")

        disconnectInternal(announce = false)
        latestTelemetry = null
        latestPose = null
        telemetrySequenceGuard.reset()
        poseSequenceGuard.reset()
        update(EveBaseLinkState.SCANNING, "أبحث فقط عن قاعدة تعلن بروتوكول EVE المتوافق.")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                connectToResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.firstOrNull()?.let(::connectToResult)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScanningInternal()
                fail("فشل البحث عن قاعدة Bluetooth: code=$errorCode")
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            main.removeCallbacks(scanTimeout)
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            return true
        } catch (error: SecurityException) {
            stopScanningInternal()
            return fail("لم يسمح Android ببحث Bluetooth: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            stopScanningInternal()
            return fail("تعذر بدء بحث Bluetooth: ${error.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun send(command: EveRobotBaseProtocol.MotorCommand): Boolean {
        if (state != EveBaseLinkState.READY) return false
        if (!hasRequiredPermissions()) {
            fail("فُقد إذن Bluetooth؛ أوقف الحركة.")
            return false
        }
        val characteristic = commandCharacteristic ?: return false
        val currentGatt = gatt ?: return false
        sequence = (sequence + 1) and 0xFF
        val frame = EveRobotBaseProtocol.encodeCommand(command, sequence)
        synchronized(writeLock) {
            if (writeInFlight) {
                // Latest motor intent wins. Never accumulate stale movement commands in a queue.
                pendingFrame = frame
                return true
            }
            val started = startWrite(currentGatt, characteristic, frame)
            if (!started) {
                writeInFlight = false
                pendingFrame = null
                fail("تعذرت كتابة أمر الحركة؛ لا تكرر الحركة دون بيانات اتصال سليمة.")
                return false
            }
            // Write-with-response is serialized through onCharacteristicWrite. A no-response
            // characteristic has no dependable completion signal, so don't wait on that callback
            // or let one command permanently block all newer stop/drive frames.
            writeInFlight = commandWriteType != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return true
    }

    @Synchronized
    fun disconnect() {
        stopMotors()
        disconnectInternal(announce = true)
    }

    fun stopMotors() {
        send(
            EveRobotBaseProtocol.MotorCommand(
                type = EveRobotBaseProtocol.CommandType.STOP,
                reason = "phone requested stop"
            )
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stopMotors()
        closed = true
        main.removeCallbacks(scanTimeout)
        stopScanningInternal()
        disconnectInternal(announce = false)
    }

    private fun connectToResult(result: ScanResult) {
        synchronized(this) {
            if (closed || state != EveBaseLinkState.SCANNING) return
            stopScanningInternal()
            if (!hasRequiredPermissions()) {
                fail("إذن Bluetooth غير متاح بعد العثور على الجهاز.")
                return
            }
            update(EveBaseLinkState.CONNECTING, "عُثر على قاعدة متوافقة؛ أتحقق من خدمتها.")
            try {
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    result.device.connectGatt(appContext, false, gattCallback)
                }
                if (gatt == null) fail("تعذر إنشاء اتصال GATT.")
            } catch (error: SecurityException) {
                fail("لم يسمح Android بالاتصال بقاعدة Bluetooth.")
            } catch (error: Exception) {
                fail("تعذر الاتصال بقاعدة EVE: ${error.javaClass.simpleName}")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized(this@EveBleRobotBase) { disconnectInternal(announce = false) }
                fail("انقطع اتصال القاعدة أو فشل: GATT status=$status. يجب أن توقف القاعدة محركاتها تلقائيًا.")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                update(EveBaseLinkState.CONFIGURING, "الاتصال قائم؛ أتحقق من خصائص الأمان والحساسات.")
                try {
                    if (!callbackGatt.discoverServices()) fail("لم يبدأ اكتشاف خدمات القاعدة.")
                } catch (error: SecurityException) {
                    fail("فُقد إذن Bluetooth أثناء اكتشاف خدمات القاعدة.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(this@EveBleRobotBase) {
                    gatt?.close()
                    gatt = null
                    commandCharacteristic = null
                    commandWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    telemetryCharacteristic = null
                    poseCharacteristic = null
                    latestTelemetry = null
                    latestPose = null
                    telemetrySequenceGuard.reset()
                    poseSequenceGuard.reset()
                    synchronized(writeLock) {
                        writeInFlight = false
                        pendingFrame = null
                    }
                }
                update(EveBaseLinkState.DISCONNECTED, "انقطع اتصال القاعدة. لا تتحرك قبل إعادة الاتصال والحصول على حساسات حديثة.")
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("تعذر قراءة خدمات قاعدة EVE.")
                return
            }
            val service = callbackGatt.getService(serviceUuid)
            val command = service?.getCharacteristic(commandUuid)
            val telemetry = service?.getCharacteristic(telemetryUuid)
            val pose = service?.getCharacteristic(poseUuid)
            val supportsWrite = command != null && (
                command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                )
            val supportsNotify = telemetry != null && (
                telemetry.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    telemetry.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                )
            val supportsPose = pose != null && (
                pose.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    pose.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                )
            if (service == null || command == null || telemetry == null || pose == null ||
                !supportsWrite || !supportsNotify || !supportsPose
            ) {
                fail("القاعدة لا توفر أوامر EVE وحساساتها وبيانات الموقع الحركي؛ لم أسمح بالحركة.")
                callbackGatt.disconnect()
                return
            }
            commandCharacteristic = command
            commandWriteType = if (command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            telemetryCharacteristic = telemetry
            poseCharacteristic = pose
            if (!enableNotifications(callbackGatt, telemetry)) {
                fail("تعذر تفعيل إشعارات حساسات العوائق والحواف.")
                callbackGatt.disconnect()
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != cccdUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("تعذر تفعيل قناة الحساسات أو الإحداثيات؛ الحركة ممنوعة.")
                callbackGatt.disconnect()
                return
            }
            when (descriptor.characteristic.uuid) {
                telemetryUuid -> {
                    // GATT permits one descriptor transaction at a time. Subscribe to fused pose
                    // only after the safety-sensor notification subscription has completed.
                    val pose = poseCharacteristic
                    if (pose == null || !enableNotifications(callbackGatt, pose)) {
                        fail("لم أستطع تفعيل بيانات الموقع الحركي؛ خريطة المنزل والحركة ممنوعتان.")
                        callbackGatt.disconnect()
                    }
                }
                poseUuid -> {
                    update(EveBaseLinkState.READY, "قاعدة EVE متصلة؛ أنتظر بيانات حساسات وإحداثيات حديثة قبل السماح بالحركة.")
                    send(
                        EveRobotBaseProtocol.MotorCommand(
                            type = EveRobotBaseProtocol.CommandType.STOP,
                            reason = "safe stop on connection"
                        )
                    )
                    send(
                        EveRobotBaseProtocol.MotorCommand(
                            type = EveRobotBaseProtocol.CommandType.REQUEST_STATUS,
                            reason = "request fresh sensor and pose state"
                        )
                    )
                }
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            receiveTelemetry(characteristic, value)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receiveTelemetry(characteristic, value.copyOf())
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != commandUuid) return
            // WRITE_TYPE_NO_RESPONSE completion callbacks may be absent or delayed; never let a
            // late callback release the in-flight lock for a newer command. The base watchdog is
            // responsible for stopping if a no-response write itself is lost.
            if (commandWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) return
            synchronized(writeLock) {
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pendingFrame = null
                    main.post { fail("فشلت كتابة أمر القاعدة؛ أوقف الحركة حتى يستقر الاتصال.") }
                    return
                }
                val pending = pendingFrame
                pendingFrame = null
                if (pending != null && state == EveBaseLinkState.READY) {
                    writeInFlight = startWrite(callbackGatt, characteristic, pending)
                    if (!writeInFlight) main.post { fail("فشلت كتابة أمر الحركة التالي.") }
                }
            }
        }
    }

    private fun receiveTelemetry(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (characteristic.uuid == telemetryUuid) {
            val parsed = EveRobotBaseProtocol.decodeTelemetry(bytes, now)
            if (parsed == null) {
                // Invalidate the prior sample before posting the state transition: the next
                // 250 ms control tick must not move on a still-young but contradicted frame.
                latestTelemetry = null
                send(EveRobotBaseProtocol.MotorCommand(
                    EveRobotBaseProtocol.CommandType.STOP,
                    reason = "invalid telemetry"
                ))
                main.post { fail("وصل إطار حساس غير صالح (الإصدار/الطول/التحقق). الحركة متوقفة حتى وصول إطار سليم.") }
                return
            }
            if (!telemetrySequenceGuard.accept(parsed.sequence)) {
                latestTelemetry = null
                send(EveRobotBaseProtocol.MotorCommand(
                    type = EveRobotBaseProtocol.CommandType.STOP,
                    reason = "replayed or out-of-order sensor telemetry"
                ))
                main.post { fail("تكررت أو عادت إلى الخلف بيانات الحساسات؛ أوقفت الحركة حتى يتصل الجهاز ويرسل إطارات جديدة.") }
                return
            }
            latestTelemetry = parsed
            main.post { if (state == EveBaseLinkState.READY) listener.onTelemetry(parsed) }
            return
        }
        if (characteristic.uuid == poseUuid) {
            val pose = EveRobotBaseProtocol.decodePose(bytes, now)
            if (pose == null) {
                main.post { fail("وصل إطار موقع حركي غير صالح؛ لن أحرّك القاعدة حتى يصل إطار صحيح.") }
                latestPose = null
                send(EveRobotBaseProtocol.MotorCommand(EveRobotBaseProtocol.CommandType.STOP, reason = "invalid pose"))
                return
            }
            if (!poseSequenceGuard.accept(pose.sequence)) {
                latestPose = null
                send(EveRobotBaseProtocol.MotorCommand(
                    type = EveRobotBaseProtocol.CommandType.STOP,
                    reason = "replayed or out-of-order odometry pose"
                ))
                main.post { fail("تكررت أو عادت إلى الخلف إحداثيات القاعدة؛ أوقفت الحركة حتى تصل إحداثيات حديثة.") }
                return
            }
            latestPose = pose
            main.post { if (state == EveBaseLinkState.READY) listener.onPose(pose) }
        }
    }

    private fun enableNotifications(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return try {
            if (!callbackGatt.setCharacteristicNotification(characteristic, true)) return false
            val descriptor = characteristic.getDescriptor(cccdUuid) ?: return false
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                callbackGatt.writeDescriptor(descriptor, value) == 0
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                callbackGatt.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun startWrite(
        currentGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(
                    characteristic,
                    frame,
                    commandWriteType
                ) == 0
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = commandWriteType
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Synchronized
    private fun stopScanningInternal() {
        main.removeCallbacks(scanTimeout)
        val callback = scanCallback ?: return
        try {
            if (hasRequiredPermissions()) adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // The scan is no longer actionable; the caller reports the permission state.
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    @Synchronized
    private fun disconnectInternal(announce: Boolean) {
        main.removeCallbacks(scanTimeout)
        stopScanningInternal()
        val current = gatt
        gatt = null
        commandCharacteristic = null
        commandWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        telemetryCharacteristic = null
        poseCharacteristic = null
        latestTelemetry = null
        latestPose = null
        telemetrySequenceGuard.reset()
        poseSequenceGuard.reset()
        synchronized(writeLock) {
            writeInFlight = false
            pendingFrame = null
        }
        try {
            if (current != null) {
                if (hasRequiredPermissions()) current.disconnect()
                current.close()
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        if (announce) update(EveBaseLinkState.DISCONNECTED, "فُصلت القاعدة بأمر المستخدم.")
    }

    private fun fail(detail: String): Boolean {
        update(EveBaseLinkState.ERROR, detail)
        return false
    }

    private fun update(newState: EveBaseLinkState, detail: String) {
        state = newState
        lastDetail = detail
        main.post { listener.onStateChanged(newState, detail) }
    }

    companion object {
        const val SCAN_TIMEOUT_MS = 12_000L
    }
}
