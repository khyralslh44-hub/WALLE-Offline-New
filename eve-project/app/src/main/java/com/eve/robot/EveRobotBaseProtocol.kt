package com.eve.robot

import kotlin.math.abs

/**
 * Hardware contract for a future EVE mobile base.
 *
 * The base firmware must expose the advertised GATT service and stop its motor driver by
 * hardware watchdog if it stops receiving fresh DRIVE frames. This protocol is intentionally
 * fail-closed: a phone may connect to a device, but it may not move it without fresh telemetry,
 * healthy motor state, all required obstacle sensors, and both downward cliff sensors.
 */
object EveRobotBaseProtocol {
    const val SERVICE_UUID_TEXT = "6f8e0001-7d5b-4d7a-ae11-2e9b00000001"
    const val COMMAND_UUID_TEXT = "6f8e0002-7d5b-4d7a-ae11-2e9b00000001"
    const val TELEMETRY_UUID_TEXT = "6f8e0003-7d5b-4d7a-ae11-2e9b00000001"

    const val MAGIC: Int = 0xE7
    const val VERSION: Int = 1
    const val TELEMETRY_TYPE: Int = 0x80
    const val TELEMETRY_FRAME_BYTES: Int = 20
    const val COMMAND_FRAME_BYTES: Int = 8

    const val FLAG_FRONT_VALID = 1 shl 0
    const val FLAG_REAR_VALID = 1 shl 1
    const val FLAG_LEFT_VALID = 1 shl 2
    const val FLAG_RIGHT_VALID = 1 shl 3
    const val FLAG_LEFT_CLIFF_VALID = 1 shl 4
    const val FLAG_RIGHT_CLIFF_VALID = 1 shl 5
    const val FLAG_BUMPER = 1 shl 6
    const val FLAG_ESTOP = 1 shl 7

    const val STATUS_MOTOR_READY = 1 shl 0
    const val STATUS_FAULT = 1 shl 1

    enum class CommandType(val wireValue: Int) {
        STOP(0x10),
        DRIVE(0x11),
        REQUEST_STATUS(0x12)
    }

    data class MotorCommand(
        val type: CommandType,
        val leftPercent: Int = 0,
        val rightPercent: Int = 0,
        /** The base firmware must stop these wheel targets after this many milliseconds. */
        val leaseMs: Int = 0,
        val reason: String = ""
    ) {
        init {
            require(leftPercent in -100..100)
            require(rightPercent in -100..100)
            require(leaseMs in 0..500)
        }
    }

    data class Telemetry(
        val sequence: Int,
        val receivedAtMs: Long,
        val batteryPercent: Int,
        val frontDistanceMm: Int?,
        val rearDistanceMm: Int?,
        val leftDistanceMm: Int?,
        val rightDistanceMm: Int?,
        val leftCliffDistanceMm: Int?,
        val rightCliffDistanceMm: Int?,
        val bumperPressed: Boolean,
        val emergencyStopPressed: Boolean,
        val motorsReady: Boolean,
        val baseFault: Boolean
    ) {
        fun isFresh(nowMs: Long, maxAgeMs: Long = MAX_TELEMETRY_AGE_MS): Boolean =
            receivedAtMs > 0L && nowMs >= receivedAtMs && nowMs - receivedAtMs <= maxAgeMs

        fun hasCompleteSafetySensors(): Boolean =
            frontDistanceMm != null && rearDistanceMm != null &&
                leftDistanceMm != null && rightDistanceMm != null &&
                leftCliffDistanceMm != null && rightCliffDistanceMm != null

        fun hasSafeFloorUnderBothCliffSensors(): Boolean =
            leftCliffDistanceMm != null && rightCliffDistanceMm != null &&
                leftCliffDistanceMm < CLIFF_EDGE_DISTANCE_MM &&
                rightCliffDistanceMm < CLIFF_EDGE_DISTANCE_MM
    }

    fun encodeCommand(command: MotorCommand, sequence: Int): ByteArray {
        val frame = ByteArray(COMMAND_FRAME_BYTES)
        frame[0] = MAGIC.toByte()
        frame[1] = VERSION.toByte()
        frame[2] = command.type.wireValue.toByte()
        frame[3] = (sequence and 0xFF).toByte()
        frame[4] = command.leftPercent.toByte()
        frame[5] = command.rightPercent.toByte()
        frame[6] = (command.leaseMs / 100).coerceIn(0, 5).toByte()
        frame[7] = xorChecksum(frame, 0, COMMAND_FRAME_BYTES - 1).toByte()
        return frame
    }

    fun decodeTelemetry(frame: ByteArray, receivedAtMs: Long): Telemetry? {
        if (frame.size != TELEMETRY_FRAME_BYTES) return null
        if ((frame[0].toInt() and 0xFF) != MAGIC) return null
        if ((frame[1].toInt() and 0xFF) != VERSION) return null
        if ((frame[2].toInt() and 0xFF) != TELEMETRY_TYPE) return null
        if ((frame[19].toInt() and 0xFF) != xorChecksum(frame, 0, 19)) return null

        val flags = frame[4].toInt() and 0xFF
        val status = frame[18].toInt() and 0xFF
        val battery = frame[5].toInt() and 0xFF
        if (battery !in 0..100) return null

        fun range(offset: Int, validFlag: Int): Int? {
            if ((flags and validFlag) == 0) return null
            val mm = unsignedShortLe(frame, offset)
            return mm.takeUnless { it == INVALID_RANGE_MM || it <= 0 || it > MAX_SENSOR_RANGE_MM }
        }

        return Telemetry(
            sequence = frame[3].toInt() and 0xFF,
            receivedAtMs = receivedAtMs,
            batteryPercent = battery,
            frontDistanceMm = range(6, FLAG_FRONT_VALID),
            rearDistanceMm = range(8, FLAG_REAR_VALID),
            leftDistanceMm = range(10, FLAG_LEFT_VALID),
            rightDistanceMm = range(12, FLAG_RIGHT_VALID),
            leftCliffDistanceMm = range(14, FLAG_LEFT_CLIFF_VALID),
            rightCliffDistanceMm = range(16, FLAG_RIGHT_CLIFF_VALID),
            bumperPressed = (flags and FLAG_BUMPER) != 0,
            emergencyStopPressed = (flags and FLAG_ESTOP) != 0,
            motorsReady = (status and STATUS_MOTOR_READY) != 0,
            baseFault = (status and STATUS_FAULT) != 0
        )
    }

    fun checksum(frame: ByteArray, endExclusive: Int = frame.size - 1): Int =
        xorChecksum(frame, 0, endExclusive)

    private fun xorChecksum(frame: ByteArray, start: Int, endExclusive: Int): Int {
        var value = 0
        for (index in start until endExclusive) value = value xor (frame[index].toInt() and 0xFF)
        return value and 0xFF
    }

    private fun unsignedShortLe(frame: ByteArray, offset: Int): Int =
        (frame[offset].toInt() and 0xFF) or ((frame[offset + 1].toInt() and 0xFF) shl 8)

    const val INVALID_RANGE_MM = 0xFFFF
    const val MAX_SENSOR_RANGE_MM = 6000
    const val MAX_TELEMETRY_AGE_MS = 600L
    const val CLIFF_EDGE_DISTANCE_MM = 145
    const val LOW_BATTERY_STOP_PERCENT = 8
}

data class EveBaseMotionDecision(
    val leftPercent: Int,
    val rightPercent: Int,
    val leaseMs: Int,
    val reason: String,
    val movingAllowed: Boolean
)

/**
 * Sensor-gated local obstacle/cliff policy. It makes no move decision from camera guesses alone.
 * The firmware must independently enforce its motor watchdog and physical emergency stop.
 */
class EveBaseNavigator {
    private enum class AvoidancePhase { NONE, RETREAT, TURN }

    private var avoidancePhase = AvoidancePhase.NONE
    private var phaseEndsAtMs = 0L
    private var turnTowardLeft = true
    private var safetyLatched = false
    private var latchedReason = ""

    @Synchronized
    fun decide(
        telemetry: EveRobotBaseProtocol.Telemetry?,
        nowMs: Long,
        autonomyEnabled: Boolean,
        personInPath: Boolean = false
    ): EveBaseMotionDecision {
        if (telemetry == null) return stop("no base telemetry")
        if (!telemetry.isFresh(nowMs)) {
            resetAvoidance()
            return stop("telemetry stale or clock moved backwards")
        }

        if (telemetry.emergencyStopPressed || telemetry.baseFault) {
            safetyLatched = true
            latchedReason = if (telemetry.emergencyStopPressed) "hardware emergency stop" else "base fault"
        }

        if (!telemetry.hasSafeFloorUnderBothCliffSensors()) {
            if (telemetry.leftCliffDistanceMm == null || telemetry.rightCliffDistanceMm == null) {
                resetAvoidance()
                return stop("cliff sensors missing or invalid")
            }
            safetyLatched = true
            latchedReason = "possible desk edge or drop detected"
        }

        if (safetyLatched) {
            resetAvoidance()
            return stop("safety latch: $latchedReason")
        }
        if (!telemetry.motorsReady) {
            resetAvoidance()
            return stop("motor driver not ready")
        }
        if (telemetry.bumperPressed) {
            resetAvoidance()
            return stop("bumper pressed")
        }
        if (telemetry.batteryPercent <= EveRobotBaseProtocol.LOW_BATTERY_STOP_PERCENT) {
            resetAvoidance()
            return stop("battery too low for safe movement")
        }
        if (!autonomyEnabled) {
            resetAvoidance()
            return stop("base autonomy disabled")
        }
        if (personInPath) {
            resetAvoidance()
            return stop("person detected in path; yield")
        }
        if (!telemetry.hasCompleteSafetySensors()) {
            resetAvoidance()
            return stop("required obstacle or cliff sensor unavailable")
        }

        val front = telemetry.frontDistanceMm!!
        val rear = telemetry.rearDistanceMm!!
        val left = telemetry.leftDistanceMm!!
        val right = telemetry.rightDistanceMm!!

        when (avoidancePhase) {
            AvoidancePhase.RETREAT -> {
                if (rear <= REAR_RETREAT_CLEARANCE_MM) {
                    resetAvoidance()
                    return stop("rear path became blocked during retreat")
                }
                if (nowMs < phaseEndsAtMs) return moving(-RETREAT_SPEED, -RETREAT_SPEED, 250, "short retreat before turn")
                avoidancePhase = AvoidancePhase.TURN
                phaseEndsAtMs = nowMs + TURN_DURATION_MS
            }
            AvoidancePhase.TURN -> {
                if (nowMs < phaseEndsAtMs) {
                    val chosenSide = if (turnTowardLeft) left else right
                    if (chosenSide <= SIDE_TURN_CLEARANCE_MM) {
                        resetAvoidance()
                        return stop("turn path is not clear")
                    }
                    val turn = if (turnTowardLeft) TURN_SPEED else -TURN_SPEED
                    return moving(-turn, turn, 250, "turn toward wider measured clearance")
                }
                resetAvoidance()
                return stop("obstacle avoidance maneuver complete; reacquiring sensors")
            }
            AvoidancePhase.NONE -> Unit
        }

        // RETREAT can transition to TURN in the branch above; execute that next phase now
        // instead of re-entering the obstacle trigger and accidentally retreating forever.
        if (avoidancePhase == AvoidancePhase.TURN) {
            val chosenSide = if (turnTowardLeft) left else right
            if (chosenSide <= SIDE_TURN_CLEARANCE_MM) {
                resetAvoidance()
                return stop("turn path is not clear")
            }
            val turn = if (turnTowardLeft) TURN_SPEED else -TURN_SPEED
            return moving(-turn, turn, 250, "turn toward wider measured clearance")
        }

        val frontBlocked = front <= FRONT_OBSTACLE_TRIGGER_MM
        val leftBlocked = left <= SIDE_OBSTACLE_TRIGGER_MM
        val rightBlocked = right <= SIDE_OBSTACLE_TRIGGER_MM
        if (frontBlocked || leftBlocked || rightBlocked) {
            turnTowardLeft = when {
                left > right && left > SIDE_TURN_CLEARANCE_MM -> true
                right > left && right > SIDE_TURN_CLEARANCE_MM -> false
                else -> return stop("no measured side clearance for an avoidance turn")
            }
            if (rear <= REAR_RETREAT_CLEARANCE_MM) {
                return stop("obstacle ahead and rear path is not clear")
            }
            avoidancePhase = AvoidancePhase.RETREAT
            phaseEndsAtMs = nowMs + RETREAT_DURATION_MS
            return moving(-RETREAT_SPEED, -RETREAT_SPEED, 250, "obstacle ahead; retreat before turning")
        }

        return moving(CRUISE_SPEED, CRUISE_SPEED, 300, "slow autonomous exploration")
    }

    @Synchronized
    fun clearSafetyLatch(telemetry: EveRobotBaseProtocol.Telemetry?, nowMs: Long): Boolean {
        val safe = telemetry != null &&
            telemetry.isFresh(nowMs) &&
            telemetry.motorsReady &&
            !telemetry.baseFault &&
            !telemetry.emergencyStopPressed &&
            !telemetry.bumperPressed &&
            telemetry.batteryPercent > EveRobotBaseProtocol.LOW_BATTERY_STOP_PERCENT &&
            telemetry.hasCompleteSafetySensors() &&
            telemetry.hasSafeFloorUnderBothCliffSensors() &&
            telemetry.frontDistanceMm!! > FRONT_OBSTACLE_TRIGGER_MM
        if (safe) {
            safetyLatched = false
            latchedReason = ""
            resetAvoidance()
        }
        return safe
    }

    @Synchronized
    fun isSafetyLatched(): Boolean = safetyLatched

    @Synchronized
    fun resetAvoidance() {
        avoidancePhase = AvoidancePhase.NONE
        phaseEndsAtMs = 0L
    }

    private fun stop(reason: String) =
        EveBaseMotionDecision(0, 0, 0, reason, false)

    private fun moving(left: Int, right: Int, lease: Int, reason: String) =
        EveBaseMotionDecision(left, right, lease, reason, true)

    companion object {
        const val FRONT_OBSTACLE_TRIGGER_MM = 360
        const val SIDE_OBSTACLE_TRIGGER_MM = 240
        const val SIDE_TURN_CLEARANCE_MM = 430
        const val REAR_RETREAT_CLEARANCE_MM = 420
        const val RETREAT_DURATION_MS = 250L
        const val TURN_DURATION_MS = 420L
        const val CRUISE_SPEED = 18
        const val RETREAT_SPEED = 16
        const val TURN_SPEED = 20
    }
}
