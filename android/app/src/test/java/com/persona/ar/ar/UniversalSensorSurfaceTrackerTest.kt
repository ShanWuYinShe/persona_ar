package com.persona.ar.ar

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class UniversalSensorSurfaceTrackerTest {

    private val fakeContext = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun `anchorToDesktop sets valid 3D world anchor coordinates`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        assertFalse(tracker.spatialAnchor.value.isAnchored)

        tracker.anchorToDesktop(0.5f, 0.65f)

        val anchor = tracker.spatialAnchor.value
        assertTrue(anchor.isAnchored)
        assertTrue(anchor.isWorldAnchored)
        assertTrue(anchor.isInView)
        assertEquals(0f, anchor.azimuthDeltaDegrees, 0.01f)
        assertTrue(anchor.worldAnchorZ < -0.2f)
        assertEquals(0.65f, anchor.distanceMeters, 0.01f)
    }

    @Test
    fun `resetAnchor clears anchored state`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        tracker.anchorToDesktop(0.5f, 0.65f)
        assertTrue(tracker.spatialAnchor.value.isAnchored)

        tracker.resetAnchor()
        val anchor = tracker.spatialAnchor.value
        assertFalse(anchor.isAnchored)
        assertFalse(anchor.isWorldAnchored)
        assertEquals("未锚定", anchor.planeTypeDescription)
    }

    @Test
    fun `scale and rotation updates stay within safe bounds`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        tracker.anchorToDesktop(0.5f, 0.65f)

        tracker.updateScale(1.5f)
        assertEquals(1.5f, tracker.spatialAnchor.value.scaleFactor, 0.01f)

        tracker.updateRotation(45f)
        assertEquals(45f, tracker.spatialAnchor.value.rotationYaw, 0.01f)

        tracker.updateRotation(360f)
        assertEquals(45f, tracker.spatialAnchor.value.rotationYaw, 0.01f)
    }
}
