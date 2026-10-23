package com.nova.robot
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt
/** Repeated accelerometer pulses trigger a playful dizziness face, not a medical symptom detector. */
internal class NovaShakeDetector(
    private val threshold: Float = 3.2f,
    private val windowMillis: Long = 1_200L,
    private val minimumPulseGapMillis: Long = 140L,
    private val cooldownMillis: Long = 9_000L,
    private val requiredPulses: Int = 3
) {
    private val pulses = ArrayDeque<Long>()
    private var lastPulseAt = Long.MIN_VALUE
    private var lastSampleAt = Long.MIN_VALUE
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN
    private var lastTriggerAt = Long.MIN_VALUE
    @Synchronized fun observe(x: Float, y: Float, z: Float, nowMillis: Long): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (lastSampleAt != Long.MIN_VALUE && nowMillis < lastSampleAt) reset()
        val magnitude = sqrt(x*x + y*y + z*z)
        val gravityDeviation = abs(magnitude - GRAVITY)
        val delta = if (lastX.isFinite() && lastY.isFinite() && lastZ.isFinite())
            sqrt((x-lastX)*(x-lastX)+(y-lastY)*(y-lastY)+(z-lastZ)*(z-lastZ)) else 0f
        lastSampleAt = nowMillis; lastX=x; lastY=y; lastZ=z
        while (pulses.isNotEmpty() && nowMillis >= pulses.peekFirst() && nowMillis-pulses.peekFirst()>windowMillis) pulses.removeFirst()
        if (gravityDeviation < threshold && delta < VECTOR_DELTA_THRESHOLD) return false
        if (lastPulseAt != Long.MIN_VALUE && nowMillis >= lastPulseAt && nowMillis-lastPulseAt<minimumPulseGapMillis) return false
        if (lastTriggerAt != Long.MIN_VALUE && nowMillis >= lastTriggerAt && nowMillis-lastTriggerAt<cooldownMillis) return false
        lastPulseAt=nowMillis; pulses.addLast(nowMillis)
        if (pulses.size < requiredPulses) return false
        pulses.clear(); lastTriggerAt=nowMillis
        return true
    }
    @Synchronized fun reset() {
        pulses.clear(); lastPulseAt=Long.MIN_VALUE; lastSampleAt=Long.MIN_VALUE
        lastX=Float.NaN; lastY=Float.NaN; lastZ=Float.NaN
    }
    private companion object { const val GRAVITY=9.80665f; const val VECTOR_DELTA_THRESHOLD=8.0f }
}
