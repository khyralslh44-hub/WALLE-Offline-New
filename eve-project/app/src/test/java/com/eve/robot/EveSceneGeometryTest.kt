package com.eve.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveSceneGeometryTest {
    @Test fun horizontalZonesAreImageRelativeAndBounded() {
        assertEquals("يسار الصورة", EveSceneGeometry.horizontalPosition(0.10f))
        assertEquals("وسط الصورة", EveSceneGeometry.horizontalPosition(0.50f))
        assertEquals("يمين الصورة", EveSceneGeometry.horizontalPosition(0.90f))
        assertEquals("يسار الصورة", EveSceneGeometry.horizontalPosition(-2f))
        assertEquals("يمين الصورة", EveSceneGeometry.horizontalPosition(4f))
        assertEquals("وسط الصورة", EveSceneGeometry.horizontalPosition(Float.NaN))
    }

    @Test fun verticalZonesAreImageRelativeAndBounded() {
        assertEquals("أعلى الصورة", EveSceneGeometry.verticalPosition(0.10f))
        assertEquals("منتصف الصورة", EveSceneGeometry.verticalPosition(0.50f))
        assertEquals("أسفل الصورة", EveSceneGeometry.verticalPosition(0.90f))
        assertEquals("منتصف الصورة", EveSceneGeometry.verticalPosition(Float.POSITIVE_INFINITY))
    }

    @Test fun apparentSizeDescribesImageOccupancyNotPhysicalSize() {
        assertEquals("كبير في الإطار", EveSceneGeometry.apparentImageSize(0.40f))
        assertEquals("متوسط في الإطار", EveSceneGeometry.apparentImageSize(0.10f))
        assertEquals("صغير في الإطار", EveSceneGeometry.apparentImageSize(0.01f))
        assertEquals("صغير في الإطار", EveSceneGeometry.apparentImageSize(Float.NaN))
    }

    @Test fun sceneDescriptionIncludesPositionSizeAndCalibratedConfidence() {
        val value = EveSceneGeometry.describe("كتاب", 0.85f, 0.18f, 0.10f, 0.876f)
        assertTrue(value.contains("كتاب"))
        assertTrue(value.contains("يمين الصورة"))
        assertTrue(value.contains("أعلى الصورة"))
        assertTrue(value.contains("متوسط في الإطار"))
        assertTrue(value.contains("87٪"))
    }

    @Test fun labelsNormalizeSeparatorsAndRepeatedWhitespace() {
        assertEquals("tabby cat", EveSceneGeometry.normalizeLabel("  Tabby__Cat  "))
    }
}
