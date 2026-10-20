package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveRobotBaseProtocolTest {
    private fun telemetry(
        at: Long = 1_000L,
        battery: Int = 85,
        front: Int? = 1_000,
        rear: Int? = 1_000,
        left: Int? = 800,
        right: Int? = 800,
        cliffLeft: Int? = 35,
        cliffRight: Int? = 36,
        bumper: Boolean = false,
        estop: Boolean = false,
        motorsReady: Boolean = true,
        fault: Boolean = false
    ) = EveRobotBaseProtocol.Telemetry(
        sequence = 4,
        receivedAtMs = at,
        batteryPercent = battery,
        frontDistanceMm = front,
        rearDistanceMm = rear,
        leftDistanceMm = left,
        rightDistanceMm = right,
        leftCliffDistanceMm = cliffLeft,
        rightCliffDistanceMm = cliffRight,
        bumperPressed = bumper,
        emergencyStopPressed = estop,
        motorsReady = motorsReady,
        baseFault = fault
    )

    private fun frame(
        flags: Int = 0x3F,
        status: Int = EveRobotBaseProtocol.STATUS_MOTOR_READY,
        front: Int = 1_000,
        rear: Int = 1_000,
        left: Int = 800,
        right: Int = 800,
        cliffLeft: Int = 35,
        cliffRight: Int = 36,
        battery: Int = 85
    ): ByteArray {
        val result = ByteArray(EveRobotBaseProtocol.TELEMETRY_FRAME_BYTES)
        result[0] = EveRobotBaseProtocol.MAGIC.toByte()
        result[1] = EveRobotBaseProtocol.VERSION.toByte()
        result[2] = EveRobotBaseProtocol.TELEMETRY_TYPE.toByte()
        result[3] = 4
        result[4] = flags.toByte()
        result[5] = battery.toByte()
        fun putShort(offset: Int, value: Int) {
            result[offset] = (value and 0xFF).toByte()
            result[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putShort(6, front)
        putShort(8, rear)
        putShort(10, left)
        putShort(12, right)
        putShort(14, cliffLeft)
        putShort(16, cliffRight)
        result[18] = status.toByte()
        result[19] = EveRobotBaseProtocol.checksum(result, 19).toByte()
        return result
    }

    @Test fun validTelemetryFrameDecodesLittleEndianDistancesAndStatus() {
        val decoded = EveRobotBaseProtocol.decodeTelemetry(frame(), 1_000L)
        assertNotNull(decoded)
        assertEquals(1_000, decoded!!.frontDistanceMm)
        assertEquals(35, decoded.leftCliffDistanceMm)
        assertEquals(85, decoded.batteryPercent)
        assertTrue(decoded.motorsReady)
        assertFalse(decoded.baseFault)
    }

    @Test fun corruptedOrWrongLengthFramesAreRejected() {
        val badChecksum = frame().also { it[6] = 20 }
        assertNull(EveRobotBaseProtocol.decodeTelemetry(badChecksum, 1_000L))
        assertNull(EveRobotBaseProtocol.decodeTelemetry(frame().copyOf(19), 1_000L))
        assertNull(EveRobotBaseProtocol.decodeTelemetry(frame().also { it[2] = 0 }, 1_000L))
    }

    @Test fun encodedMotorFramesAreBoundedAndCarryChecksumAndLease() {
        val command = EveRobotBaseProtocol.MotorCommand(
            type = EveRobotBaseProtocol.CommandType.DRIVE,
            leftPercent = 18,
            rightPercent = 18,
            leaseMs = 300
        )
        val frame = EveRobotBaseProtocol.encodeCommand(command, sequence = 9)
        assertEquals(EveRobotBaseProtocol.COMMAND_FRAME_BYTES, frame.size)
        assertEquals(9, frame[3].toInt() and 0xFF)
        assertEquals(3, frame[6].toInt() and 0xFF)
        assertEquals(EveRobotBaseProtocol.checksum(frame, 7), frame[7].toInt() and 0xFF)
    }

    @Test fun navigatorMovesSlowlyOnlyWithFreshCompleteHealthySensors() {
        val nav = EveBaseNavigator()
        val decision = nav.decide(telemetry(), nowMs = 1_100L, autonomyEnabled = true)
        assertTrue(decision.movingAllowed)
        assertEquals(EveBaseNavigator.CRUISE_SPEED, decision.leftPercent)
        assertEquals(EveBaseNavigator.CRUISE_SPEED, decision.rightPercent)

        val stale = nav.decide(telemetry(at = 100L), nowMs = 1_000L, autonomyEnabled = true)
        assertFalse(stale.movingAllowed)
        assertEquals(0, stale.leftPercent)
    }

    @Test fun cliffDetectionLatchesStopUntilExplicitSafeReset() {
        val nav = EveBaseNavigator()
        val edge = telemetry(cliffLeft = 220, cliffRight = 35)
        val decision = nav.decide(edge, nowMs = 1_100L, autonomyEnabled = true)
        assertFalse(decision.movingAllowed)
        assertTrue(decision.reason.contains("safety latch"))
        assertTrue(nav.isSafetyLatched())

        val safe = telemetry(at = 1_200L)
        assertFalse(nav.decide(safe, nowMs = 1_210L, autonomyEnabled = true).movingAllowed)
        assertTrue(nav.clearSafetyLatch(safe, 1_220L))
        assertFalse(nav.isSafetyLatched())
        assertTrue(nav.decide(telemetry(at = 1_300L), nowMs = 1_310L, autonomyEnabled = true).movingAllowed)
    }

    @Test fun missingCliffOrRangeSensorNeverAllowsMovement() {
        val nav = EveBaseNavigator()
        assertFalse(nav.decide(telemetry(cliffRight = null), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(front = null), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(rear = null), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(left = null), 1_100L, true).movingAllowed)
    }

    @Test fun obstacleCausesMeasuredRetreatThenTurnRatherThanBlindForwardMotion() {
        val nav = EveBaseNavigator()
        val blocked = telemetry(front = 250, rear = 900, left = 900, right = 520)
        val retreat = nav.decide(blocked, 1_000L, true)
        assertTrue(retreat.movingAllowed)
        assertTrue(retreat.leftPercent < 0 && retreat.rightPercent < 0)

        val turn = nav.decide(blocked.copy(receivedAtMs = 1_260L), 1_260L, true)
        assertTrue(turn.movingAllowed)
        assertTrue(turn.leftPercent != turn.rightPercent)
        assertEquals("turn toward wider measured clearance", turn.reason)
    }

    @Test fun personInPathLowBatteryBumperEstopFaultAndDisabledModeAllStop() {
        val nav = EveBaseNavigator()
        assertFalse(nav.decide(telemetry(), 1_100L, true, personInPath = true).movingAllowed)
        assertFalse(nav.decide(telemetry(battery = 8), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(bumper = true), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(estop = true), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(fault = true), 1_100L, true).movingAllowed)
        assertFalse(nav.decide(telemetry(), 1_100L, false).movingAllowed)
    }

    @Test fun anUnsafeOrStaleStateCannotClearTheSafetyLatch() {
        val nav = EveBaseNavigator()
        nav.decide(telemetry(cliffLeft = 220), 1_100L, true)
        assertFalse(nav.clearSafetyLatch(telemetry(at = 800L, cliffLeft = 35), 1_500L))
        assertFalse(nav.clearSafetyLatch(telemetry(cliffLeft = 180), 1_200L))
        assertTrue(nav.isSafetyLatched())
    }
}
