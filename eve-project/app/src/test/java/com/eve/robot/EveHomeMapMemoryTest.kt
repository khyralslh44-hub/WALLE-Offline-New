package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveHomeMapMemoryTest {
    private fun telemetry(at: Long = 1_000L) = EveRobotBaseProtocol.Telemetry(
        sequence = 1,
        receivedAtMs = at,
        batteryPercent = 90,
        frontDistanceMm = 1_000,
        rearDistanceMm = 900,
        leftDistanceMm = 700,
        rightDistanceMm = 850,
        leftCliffDistanceMm = 35,
        rightCliffDistanceMm = 36,
        bumperPressed = false,
        emergencyStopPressed = false,
        motorsReady = true,
        baseFault = false
    )

    private fun pose(
        at: Long = 1_000L,
        x: Int = 0,
        y: Int = 0,
        heading: Float = 0f
    ) = EveRobotBaseProtocol.Pose(
        sequence = 1,
        receivedAtMs = at,
        xMm = x,
        yMm = y,
        headingDegrees = heading
    )

    @Test fun mapUpdatesOnlyFromFreshPoseAndCompleteFreshSensors() {
        val map = EveHomeMapMemory()
        val result = map.observe(
            pose = pose(),
            telemetry = telemetry(),
            room = EveRoomType.UNKNOWN,
            nowMs = 1_000L
        )
        assertNotNull(result)
        assertTrue(result!!.knownCells > 1)
        assertTrue(result.freeCells > 0)
        assertNotNull(map.bestFrontierHeadingDegrees(pose(), 1_000L))

        assertNull(
            map.observe(
                pose = pose(at = 100L),
                telemetry = telemetry(at = 100L),
                room = EveRoomType.UNKNOWN,
                nowMs = 1_000L
            )
        )
    }

    @Test fun roomHypothesesAreAssociatedWithLocalPoseCells() {
        val map = EveHomeMapMemory()
        val result = map.observe(
            pose = pose(),
            telemetry = telemetry(),
            room = EveRoomType.KITCHEN,
            nowMs = 1_000L
        )
        assertNotNull(result)
        assertEquals(EveRoomType.KITCHEN, result!!.currentRoom)
        assertTrue(result.namedRoomCells > 0)
    }

    @Test fun movingThroughCoordinatesAddsExploredCellsAndCanChangeRoom() {
        val map = EveHomeMapMemory()
        map.observe(pose(), telemetry(), EveRoomType.LIVING_ROOM, 1_000L)
        val later = map.observe(
            pose(at = 1_500L, x = 900, y = 0, heading = 0f),
            telemetry(at = 1_500L),
            EveRoomType.KITCHEN,
            1_500L
        )
        assertNotNull(later)
        assertTrue(later!!.knownCells > 1)
        assertEquals(EveRoomType.KITCHEN, later.currentRoom)
    }

    @Test fun mappedFrontierHeadingIsAWorldAngleInDegrees() {
        val map = EveHomeMapMemory()
        val start = pose()
        map.observe(start, telemetry(), EveRoomType.UNKNOWN, 1_000L)
        val target = map.bestFrontierHeadingDegrees(start, 1_000L)
        assertNotNull(target)
        assertTrue(target!!.isFinite())
        assertTrue(target in 0f..360f)
    }
}
