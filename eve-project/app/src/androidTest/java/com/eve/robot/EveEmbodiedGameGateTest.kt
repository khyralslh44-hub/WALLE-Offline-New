package com.eve.robot

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EveEmbodiedGameGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanGateState() {
        EveEmbodiedGameGate.clear(context)
    }

    @Test
    fun bodyGamesStayLockedWithoutAReadyBase() {
        EveEmbodiedGameGate.clear(context)
        assertFalse(EveEmbodiedGameGate.evaluate(context).allowed)
    }

    @Test
    fun bodyGamesStayLockedWhenTelemetryOrPoseIsStale() {
        EveEmbodiedGameGate.clear(context)
        val now = SystemClock.elapsedRealtime()
        EveEmbodiedGameGate.publishConnectionState(context, EveBaseLinkState.READY, "ready")
        val old = (now - EveRobotBaseProtocol.MAX_TELEMETRY_AGE_MS - 50L).coerceAtLeast(1L)
        EveEmbodiedGameGate.publishTelemetry(context, safeTelemetry(old))
        EveEmbodiedGameGate.publishPose(context, EveRobotBaseProtocol.Pose(1, old, 0, 0, 0f))
        assertFalse(EveEmbodiedGameGate.evaluate(context, now).allowed)
    }

    @Test
    fun bodyGamesStayLockedIfEitherCliffSensorReportsUnsafeFloor() {
        EveEmbodiedGameGate.clear(context)
        val now = SystemClock.elapsedRealtime()
        EveEmbodiedGameGate.publishConnectionState(context, EveBaseLinkState.READY, "ready")
        EveEmbodiedGameGate.publishTelemetry(
            context,
            safeTelemetry(now).copy(leftCliffDistanceMm = EveRobotBaseProtocol.CLIFF_EDGE_DISTANCE_MM)
        )
        EveEmbodiedGameGate.publishPose(context, EveRobotBaseProtocol.Pose(1, now, 0, 0, 0f))
        assertFalse(EveEmbodiedGameGate.evaluate(context, now).allowed)
    }

    @Test
    fun bodyGamesOpenOnlyWithFreshCompleteSafeTelemetryAndPose() {
        EveEmbodiedGameGate.clear(context)
        val now = SystemClock.elapsedRealtime()
        EveEmbodiedGameGate.publishConnectionState(context, EveBaseLinkState.READY, "ready")
        EveEmbodiedGameGate.publishTelemetry(context, safeTelemetry(now))
        EveEmbodiedGameGate.publishPose(context, EveRobotBaseProtocol.Pose(1, now, 0, 0, 0f))
        val result = EveEmbodiedGameGate.evaluate(context, now)
        assertTrue(result.reasonArabic, result.allowed)
        assertTrue(result.batteryPercent > EveRobotBaseProtocol.LOW_BATTERY_STOP_PERCENT)
    }

    private fun safeTelemetry(receivedAtMs: Long) = EveRobotBaseProtocol.Telemetry(
        sequence = 1,
        receivedAtMs = receivedAtMs,
        batteryPercent = 80,
        frontDistanceMm = 800,
        rearDistanceMm = 800,
        leftDistanceMm = 700,
        rightDistanceMm = 700,
        leftCliffDistanceMm = 90,
        rightCliffDistanceMm = 90,
        bumperPressed = false,
        emergencyStopPressed = false,
        motorsReady = true,
        baseFault = false
    )
}
