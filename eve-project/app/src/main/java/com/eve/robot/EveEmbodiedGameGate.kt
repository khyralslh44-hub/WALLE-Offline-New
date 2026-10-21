package com.eve.robot

import android.content.Context
import android.os.SystemClock

/**
 * Fail-closed admission check for games that claim to use EVE's physical body.
 *
 * This does not command motors. A game may start only when a compatible base is READY and
 * both its safety telemetry and fused odometry are fresh. The real base firmware must still
 * enforce its own stop/watchdog independently of the phone.
 */
data class EveEmbodiedGameReadiness(
    val allowed: Boolean,
    val reasonArabic: String,
    val batteryPercent: Int = -1,
    val telemetryAgeMs: Long = Long.MAX_VALUE,
    val poseAgeMs: Long = Long.MAX_VALUE
)

object EveEmbodiedGameGate {
    private const val PREFS = "eve_embodied_game_gate"
    private const val KEY_STATE = "link_state"
    private const val KEY_DETAIL = "link_detail"
    private const val KEY_TELEMETRY_AT = "telemetry_at_elapsed_ms"
    private const val KEY_POSE_AT = "pose_at_elapsed_ms"
    private const val KEY_BATTERY = "battery_percent"
    private const val KEY_SENSORS_COMPLETE = "sensors_complete"
    private const val KEY_FLOOR_SAFE = "floor_safe"
    private const val KEY_MOTORS_READY = "motors_ready"
    private const val KEY_BASE_FAULT = "base_fault"
    private const val KEY_BUMPER = "bumper"
    private const val KEY_ESTOP = "estop"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun publishConnectionState(context: Context, state: EveBaseLinkState, detail: String) {
        val edit = prefs(context).edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_DETAIL, detail.take(220))
        // Require brand-new sensor and pose frames after any reconnect or partial connection.
        if (state != EveBaseLinkState.READY) {
            edit.putLong(KEY_TELEMETRY_AT, 0L)
                .putLong(KEY_POSE_AT, 0L)
        }
        edit.apply()
    }

    @Synchronized
    fun publishTelemetry(context: Context, telemetry: EveRobotBaseProtocol.Telemetry) {
        prefs(context).edit()
            .putLong(KEY_TELEMETRY_AT, telemetry.receivedAtMs)
            .putInt(KEY_BATTERY, telemetry.batteryPercent)
            .putBoolean(KEY_SENSORS_COMPLETE, telemetry.hasCompleteSafetySensors())
            .putBoolean(KEY_FLOOR_SAFE, telemetry.hasSafeFloorUnderBothCliffSensors())
            .putBoolean(KEY_MOTORS_READY, telemetry.motorsReady)
            .putBoolean(KEY_BASE_FAULT, telemetry.baseFault)
            .putBoolean(KEY_BUMPER, telemetry.bumperPressed)
            .putBoolean(KEY_ESTOP, telemetry.emergencyStopPressed)
            .apply()
    }

    @Synchronized
    fun publishPose(context: Context, pose: EveRobotBaseProtocol.Pose) {
        prefs(context).edit().putLong(KEY_POSE_AT, pose.receivedAtMs).apply()
    }

    fun evaluate(
        context: Context,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): EveEmbodiedGameReadiness {
        val p = prefs(context)
        val state = p.getString(KEY_STATE, EveBaseLinkState.DISCONNECTED.name)
            ?: EveBaseLinkState.DISCONNECTED.name
        val detail = p.getString(KEY_DETAIL, "").orEmpty()
        val telemetryAt = p.getLong(KEY_TELEMETRY_AT, 0L)
        val poseAt = p.getLong(KEY_POSE_AT, 0L)
        val telemetryAge = if (telemetryAt in 1L..nowElapsedMs) nowElapsedMs - telemetryAt else Long.MAX_VALUE
        val poseAge = if (poseAt in 1L..nowElapsedMs) nowElapsedMs - poseAt else Long.MAX_VALUE
        val battery = p.getInt(KEY_BATTERY, -1)

        fun blocked(reason: String) = EveEmbodiedGameReadiness(
            false, reason, battery, telemetryAge, poseAge
        )

        if (state != EveBaseLinkState.READY.name) {
            return blocked(
                if (detail.isNotBlank()) "الجسد غير متصل وجاهز: $detail"
                else "اتصلي أولًا بقاعدة EVE المتوافقة."
            )
        }
        if (telemetryAge > EveRobotBaseProtocol.MAX_TELEMETRY_AGE_MS) {
            return blocked("ألعاب الجسد مقفلة: قراءات الحساسات غير موجودة أو قديمة.")
        }
        if (poseAge > EveRobotBaseProtocol.MAX_TELEMETRY_AGE_MS) {
            return blocked("ألعاب الجسد مقفلة: بيانات موقع القاعدة غير حديثة.")
        }
        if (!p.getBoolean(KEY_SENSORS_COMPLETE, false)) {
            return blocked("ألعاب الجسد مقفلة: أحد حساسات العوائق أو الحواف لا يرسل قراءة موثوقة.")
        }
        if (!p.getBoolean(KEY_FLOOR_SAFE, false)) {
            return blocked("ألعاب الجسد مقفلة: قراءة أحد حساسي الحواف غير آمنة.")
        }
        if (!p.getBoolean(KEY_MOTORS_READY, false)) {
            return blocked("ألعاب الجسد مقفلة: محركات القاعدة ليست جاهزة.")
        }
        if (p.getBoolean(KEY_BASE_FAULT, true)) {
            return blocked("ألعاب الجسد مقفلة: القاعدة تبلغ عن عطل.")
        }
        if (p.getBoolean(KEY_BUMPER, true) || p.getBoolean(KEY_ESTOP, true)) {
            return blocked("ألعاب الجسد مقفلة: زر التصادم أو إيقاف الطوارئ مفعّل.")
        }
        if (battery <= EveRobotBaseProtocol.LOW_BATTERY_STOP_PERCENT) {
            return blocked("ألعاب الجسد مقفلة: شحن القاعدة منخفض.")
        }
        return EveEmbodiedGameReadiness(
            true,
            "اتصال الجسد والحساسات وموقع القاعدة حديث وآمن لبدء لعبة بصرية ثابتة.",
            battery,
            telemetryAge,
            poseAge
        )
    }

    /** Test helper and recovery path for a fresh installation; it never connects hardware. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
