package com.eve.robot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import org.junit.runner.RunWith

/** Actual on-device Pose Landmarker Lite inference; no service or camera upload is involved. */
@RunWith(AndroidJUnit4::class)
class PoseVisionIntegrationTest {
    @Test
    fun bundledPoseModelLoadsAndRunsOfflineOnARealImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val bitmap = requireNotNull(
            testContext.assets.open("face_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        ) { "Pinned image fixture could not be decoded." }
        val appContext = instrumentation.targetContext
        val modelBytes = appContext.assets.open("pose_landmarker_lite.task").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
            put(modelBytes)
            rewind()
        }
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetBuffer(modelBuffer)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.45f)
            .setMinPosePresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .build()
        val landmarker = PoseLandmarker.createFromOptions(appContext, options)
        try {
            val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
            assertNotNull("Pose Landmarker inference should return a result even when no full body is visible.", result)
            val poses = result.landmarks()
            assertTrue("The single-pose model must not return more than one person pose.", poses.size <= 1)
            var jointCount = 0
            if (poses.isNotEmpty()) {
                val points = poses.first().map { EvePosePoint(it.x(), it.y()) }
                assertTrue("A pose should contain the 33 documented body landmarks.", points.size == 33)
                val angles = EvePoseAnglesAnalyzer.analyze(points, bitmap.width, bitmap.height)
                assertNotNull("Body joints should produce a bounded geometric summary.", angles)
                assertTrue(angles!!.jointMotionScore in 0f..1f)
                jointCount = points.size
            }
            val marker = "POSE_MODEL_PASS detections=${poses.size} landmarks=$jointCount offline=true"
            Log.i("EVE_TEST", marker)
            System.out.println(marker)
        } finally {
            landmarker.close()
            bitmap.recycle()
        }
    }
}
