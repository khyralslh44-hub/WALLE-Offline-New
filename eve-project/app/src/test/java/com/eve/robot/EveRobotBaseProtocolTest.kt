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

    @Test fun sequenceGuardRejectsDuplicateAndBackwardFramesButAllowsWraparound() {
        val guard = EveBaseFrameSequenceGuard()
        assertTrue(guard.accept(254))
        assertTrue(guard.accept(255))
        assertTrue(guard.accept(0))
        assertFalse(guard.accept(0))
        assertFalse(guard.accept(255))
        assertTrue(guard.accept(1))
        guard.reset()
        assertTrue(guard.accept(10))
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
        val quarterSecondLease = EveRobotBaseProtocol.encodeCommand(
            command.copy(leaseMs = 250),
            sequence = 10
        )
        assertEquals("250 ms must round up to a 300 ms watchdog lease", 3, quarterSecondLease[6].toInt() and 0xFF)
        assertEquals(EveRobotBaseProtocol.checksum(frame, 7), frame[7].toInt() and 0xFF)
    }

    private fun poseFrame(
        xMm: Int = 1_250,
        yMm: Int = -750,
        headingCentiDegrees: Int = 9_000,
        flags: Int = EveRobotBaseProtocol.POSE_VALID_FLAG
    ): ByteArray {
        val result = ByteArray(EveRobotBaseProtocol.POSE_FRAME_BYTES)
        result[0] = EveRobotBaseProtocol.MAGIC.toByte()
        result[1] = EveRobotBaseProtocol.VERSION.toByte()
        result[2] = EveRobotBaseProtocol.POSE_TYPE.toByte()
        result[3] = 7
        fun putInt(offset: Int, value: Int) {
            result[offset] = (value and 0xFF).toByte()
            result[offset + 1] = ((value shr 8) and 0xFF).toByte()
            result[offset + 2] = ((value shr 16) and 0xFF).toByte()
            result[offset + 3] = (value shr 24).toByte()
        }
        result[4] = (xMm and 0xFF).toByte()
        result[5] = ((xMm shr 8) and 0xFF).toByte()
        result[6] = ((xMm shr 16) and 0xFF).toByte()
        result[7] = (xMm shr 24).toByte()
        putInt(8, yMm)
        result[12] = (headingCentiDegrees and 0xFF).toByte()
        result[13] = ((headingCentiDegrees shr 8) and 0xFF).toByte()
        result[14] = flags.toByte()
        result[15] = EveRobotBaseProtocol.checksum(result, 15).toByte()
        return result
    }

    @Test fun fusedPoseFrameDecodesSignedCoordinatesAndHeading() {
        val pose = EveRobotBaseProtocol.decodePose(poseFrame(), 1_000L)
        assertNotNull(pose)
        assertEquals(1_250, pose!!.xMm)
        assertEquals(-750, pose.yMm)
        assertEquals(90f, pose.headingDegrees, 0.01f)
        assertTrue(pose.isFresh(1_500L))
        assertFalse(pose.isFresh(1_700L))
    }

    @Test fun invalidPoseFramesCannotFeedTheMap() {
        assertNull(EveRobotBaseProtocol.decodePose(poseFrame().also { it[7] = 25 }, 1_000L))
        assertNull(EveRobotBaseProtocol.decodePose(poseFrame(flags = 0), 1_000L))
        assertNull(
            EveRobotBaseProtocol.decodePose(
                poseFrame(headingCentiDegrees = 22_000),
                1_000L
            )
        )
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

    @Test fun mappedFrontierCanSteerOnlyTowardAConfirmedClearSide() {
        val nav = EveBaseNavigator()
        val telemetry = telemetry(left = 900, right = 800, front = 1_000)
        val turn = nav.decide(
            telemetry = telemetry,
            nowMs = 1_100L,
            autonomyEnabled = true,
            preferredHeadingDegrees = 90f,
            currentHeadingDegrees = 0f
        )
        assertTrue(turn.movingAllowed)
        assertEquals("align toward unexplored mapped frontier", turn.reason)
        assertTrue(turn.leftPercent < 0 && turn.rightPercent > 0)

        val blocked = nav.decide(
            telemetry = telemetry.copy(leftDistanceMm = 300),
            nowMs = 1_200L,
            autonomyEnabled = true,
            preferredHeadingDegrees = 90f,
            currentHeadingDegrees = 0f
        )
        assertFalse(blocked.movingAllowed)
        assertTrue(blocked.reason.contains("side clearance"))
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
