package com.eve.robot

import java.util.Locale

/**
 * Conservative spatial wording for 2-D camera detections.
 * It describes where a box appears in the image; it does not infer physical distance or object size.
 */
object EveSceneGeometry {
    fun horizontalPosition(centerX: Float): String {
        val x = centerX.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        return when {
            x < 0.34f -> "يسار الصورة"
            x > 0.66f -> "يمين الصورة"
            else -> "وسط الصورة"
        }
    }

    fun verticalPosition(centerY: Float): String {
        val y = centerY.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        return when {
            y < 0.34f -> "أعلى الصورة"
            y > 0.66f -> "أسفل الصورة"
            else -> "منتصف الصورة"
        }
    }

    fun apparentImageSize(areaFraction: Float): String {
        val area = areaFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return when {
            area >= 0.20f -> "كبير في الإطار"
            area >= 0.06f -> "متوسط في الإطار"
            else -> "صغير في الإطار"
        }
    }

    fun describe(
        labelArabic: String,
        centerX: Float,
        centerY: Float,
        areaFraction: Float,
        confidence: Float
    ): String {
        val score = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return "${labelArabic.trim().ifBlank { "جسم غير محدد" }}: ${horizontalPosition(centerX)}، ${verticalPosition(centerY)}، ${apparentImageSize(areaFraction)} (ثقة ${(score * 100f).toInt()}٪)"
    }

    /** Normalizes ImageNet labels for stable de-duplication before passing them to local dialogue. */
    fun normalizeLabel(label: String): String =
        label.trim().lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
}
