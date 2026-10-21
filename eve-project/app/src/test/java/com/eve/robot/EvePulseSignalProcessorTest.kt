package com.eve.robot

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EvePulseSignalProcessorTest {
    @Test
    fun clean72BpmWaveWithSlowExposureDriftProducesApproximateEstimate() {
        val processor = EvePulseSignalProcessor()
        val start = 10_000L
        val fs = 25.0
        for (i in 0 until 400) {
            val t = i / fs
            val pulse = 0.80 + 0.018 * sin(2.0 * PI * 1.2 * t)
            val slowExposureDrift = 0.003 * sin(2.0 * PI * 0.08 * t)
            val frameNoise = 0.0005 * sin(i * 2.31)
            processor.addSample(start + i * 40L, pulse + slowExposureDrift + frameNoise)
        }

        val result = processor.estimate(start + 399 * 40L)
        assertNotNull(result.bpm)
        assertTrue(kotlin.math.abs(result.bpm!! - 72) <= 4)
        assertTrue(result.periodicity >= 0.30)
    }

    @Test
    fun randomNoiseDoesNotBecomeANumber() {
        val processor = EvePulseSignalProcessor()
        val random = Random(42)
        val start = 50_000L
        for (i in 0 until 400) {
            processor.addSample(start + i * 40L, 0.7 + random.nextDouble(-0.004, 0.004))
        }

        val result = processor.estimate(start + 399 * 40L)
        assertNull(result.bpm)
    }

    @Test
    fun insufficientDurationNeverReturnsEstimate() {
        val processor = EvePulseSignalProcessor()
        val start = 1_000L
        for (i in 0 until 100) {
            val value = 0.8 + 0.01 * sin(i * 2.0 * PI / 20.0)
            processor.addSample(start + i * 40L, value)
        }

        val result = processor.estimate(start + 99 * 40L)
        assertNull(result.bpm)
    }

    @Test
    fun duplicateAndOutOfOrderTimestampsAreIgnored() {
        val processor = EvePulseSignalProcessor()
        processor.addSample(1_000L, 0.5)
        processor.addSample(1_000L, 0.7)
        processor.addSample(900L, 0.9)
        assertTrue(processor.sampleCount() == 1)
    }
}
