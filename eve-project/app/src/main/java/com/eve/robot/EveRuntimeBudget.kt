package com.eve.robot

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * A sampling policy, not a feature switch: every capability has a positive interval in every
 * profile. Power saver changes how often the full models run, never whether they exist or run.
 */
enum class EveRuntimeProfile { BALANCED, POWER_SAVER }

data class EveInferenceCadence(
    val cameraFrameIntervalMs: Long,
    val objectDetectionIntervalMs: Long,
    val imageClassificationIntervalMs: Long,
    val poseInferenceIntervalMs: Long,
    val faceEmotionIntervalMs: Long,
    val handGestureIntervalMs: Long,
    val faceIdentityIntervalMs: Long
) {
    init {
        require(
            listOf(
                cameraFrameIntervalMs,
                objectDetectionIntervalMs,
                imageClassificationIntervalMs,
                poseInferenceIntervalMs,
                faceEmotionIntervalMs,
                handGestureIntervalMs,
                faceIdentityIntervalMs
            ).all { it > 0L }
        ) { "All EVE perception capabilities must remain scheduled in every runtime profile." }
    }
}

object EveRuntimeCadences {
    // Balanced deliberately retains the existing model cadence used by EVE today.
    val BALANCED = EveInferenceCadence(
        cameraFrameIntervalMs = 400L,
        objectDetectionIntervalMs = 1_400L,
        imageClassificationIntervalMs = 4_500L,
        poseInferenceIntervalMs = 2_200L,
        faceEmotionIntervalMs = 400L,
        handGestureIntervalMs = 400L,
        faceIdentityIntervalMs = 2_800L
    )

    // Under Android battery saver or genuine memory pressure, all models keep running less often.
    val POWER_SAVER = EveInferenceCadence(
        cameraFrameIntervalMs = 650L,
        objectDetectionIntervalMs = 2_400L,
        imageClassificationIntervalMs = 7_000L,
        poseInferenceIntervalMs = 3_500L,
        faceEmotionIntervalMs = 900L,
        handGestureIntervalMs = 900L,
        faceIdentityIntervalMs = 5_000L
    )

    fun profileFor(powerSaveMode: Boolean, lowMemory: Boolean, lowRamDevice: Boolean): EveRuntimeProfile =
        if (powerSaveMode || lowMemory || lowRamDevice) {
            EveRuntimeProfile.POWER_SAVER
        } else {
            EveRuntimeProfile.BALANCED
        }

    fun cadenceFor(profile: EveRuntimeProfile): EveInferenceCadence =
        when (profile) {
            EveRuntimeProfile.BALANCED -> BALANCED
            EveRuntimeProfile.POWER_SAVER -> POWER_SAVER
        }
}

/**
 * Refreshes Android resource pressure infrequently. It does no inference and never blocks UI work.
 */
class EveRuntimeBudget(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var selectedProfile = EveRuntimeProfile.BALANCED
    @Volatile private var selectedCadence = EveRuntimeCadences.BALANCED
    private var lastRefreshAtMs = Long.MIN_VALUE

    @Synchronized
    fun cadence(nowElapsedMs: Long = SystemClock.elapsedRealtime()): EveInferenceCadence {
        val now = nowElapsedMs.coerceAtLeast(0L)
        if (lastRefreshAtMs == Long.MIN_VALUE ||
            now < lastRefreshAtMs ||
            now - lastRefreshAtMs >= PROFILE_REFRESH_INTERVAL_MS
        ) {
            val memory = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memory)
            val next = EveRuntimeCadences.profileFor(
                powerSaveMode = powerManager.isPowerSaveMode,
                lowMemory = memory.lowMemory,
                lowRamDevice = activityManager.isLowRamDevice
            )
            if (next != selectedProfile) {
                selectedProfile = next
                selectedCadence = EveRuntimeCadences.cadenceFor(next)
            }
            lastRefreshAtMs = now
        }
        return selectedCadence
    }

    fun profile(): EveRuntimeProfile = selectedProfile

    companion object {
        const val PROFILE_REFRESH_INTERVAL_MS = 20_000L
    }
}
