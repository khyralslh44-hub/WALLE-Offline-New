package com.eve.robot

import kotlin.math.acos
import kotlin.math.sqrt

/** Lightweight, privacy-preserving joint-angle summary; no pixels or landmark arrays are retained. */
data class EvePosePoint(val x: Float, val y: Float)

data class EvePoseAngles(
    val leftKnee: Float?,
    val rightKnee: Float?,
    val leftElbow: Float?,
    val rightElbow: Float?,
    val jointMotionScore: Float
) {
    fun toActivityEvidence(): PoseActivityEvidence = PoseActivityEvidence(
        leftKneeAngleDegrees = leftKnee,
        rightKneeAngleDegrees = rightKnee,
        leftElbowAngleDegrees = leftElbow,
        rightElbowAngleDegrees = rightElbow,
        jointMotionScore = jointMotionScore
    )
}

data class PoseActivityEvidence(
    val leftKneeAngleDegrees: Float?,
    val rightKneeAngleDegrees: Float?,
    val leftElbowAngleDegrees: Float?,
    val rightElbowAngleDegrees: Float?,
    val jointMotionScore: Float
)

object EvePoseAnglesAnalyzer {
    /**
     * Uses MediaPipe's 33 normalized landmarks. Coordinates are scaled by frame dimensions so
     * joint angles are not distorted by a non-square camera image.
     * Landmark indices follow the documented Pose Landmarker topology:
     * elbows 11/13/15 and 12/14/16; hips/knees/ankles 23/25/27 and 24/26/28.
     */
    fun analyze(
        landmarks: List<EvePosePoint>,
        frameWidth: Int,
        frameHeight: Int,
        previous: EvePoseAngles? = null
    ): EvePoseAngles? {
        if (frameWidth <= 0 || frameHeight <= 0 || landmarks.size < 29) return null
        if (landmarks.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        fun px(index: Int) = EvePosePoint(
            landmarks[index].x * frameWidth,
            landmarks[index].y * frameHeight
        )
        val leftKnee = angleDegrees(px(23), px(25), px(27))
        val rightKnee = angleDegrees(px(24), px(26), px(28))
        val leftElbow = angleDegrees(px(11), px(13), px(15))
        val rightElbow = angleDegrees(px(12), px(14), px(16))
        val angles = listOf(leftKnee, rightKnee, leftElbow, rightElbow)
        val prior = previous?.let { listOf(it.leftKnee, it.rightKnee, it.leftElbow, it.rightElbow) }
        val changes = if (prior == null) emptyList() else angles.zip(prior).mapNotNull { (a, b) ->
            if (a != null && b != null) kotlin.math.abs(a - b) else null
        }
        val motion = if (changes.isEmpty()) 0f else
            (changes.average().toFloat() / 55f).coerceIn(0f, 1f)
        return EvePoseAngles(leftKnee, rightKnee, leftElbow, rightElbow, motion)
    }

    fun angleDegrees(a: EvePosePoint, b: EvePosePoint, c: EvePosePoint): Float? {
        if (listOf(a.x, a.y, b.x, b.y, c.x, c.y).any { !it.isFinite() }) return null
        val abX = a.x - b.x
        val abY = a.y - b.y
        val cbX = c.x - b.x
        val cbY = c.y - b.y
        val abLength = sqrt(abX * abX + abY * abY)
        val cbLength = sqrt(cbX * cbX + cbY * cbY)
        if (abLength < 1e-4f || cbLength < 1e-4f) return null
        val cosine = ((abX * cbX + abY * cbY) / (abLength * cbLength)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 180f)
    }
}
