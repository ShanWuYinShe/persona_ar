package com.persona.ar.character.animation

import com.persona.ar.data.model.EmotionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRigTest {

    private fun faceRig(): FaceRig = FaceRig(arrayOf(
        "Face.M_F00_000_00_Fcl_ALL_Joy",
        "Face.M_F00_000_00_Fcl_BRW_Sorrow",
        "Face.M_F00_000_00_Fcl_EYE_Joy",
        "Face.M_F00_000_00_Fcl_EYE_Close",
        "Face.M_F00_000_00_Fcl_MTH_Joy",
        "Face.M_F00_000_00_Fcl_MTH_Neutral",
        "Face.M_F00_000_00_Fcl_MTH_A",
        "Face.M_F00_000_00_Fcl_ALL_Angry",
        "Face.M_F00_000_00_Fcl_MTH_U",
        "Face.M_F00_000_00_Fcl_HA_Fung1"
    ))

    @Test
    fun `smile sets joy morph weights`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 0f, 0f)
        assertTrue("ALL_Joy 应被激活", out[0] > 0.2f)
        assertTrue("EYE_Joy 应被激活", out[2] > 0.2f)
    }

    @Test
    fun `pout drives duck mouth and angry face`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "POUT", EmotionType.ANGRY, 0f, 0f)
        assertTrue("ALL_Angry 应有鲜明形变", out[7] > 0.8f)
        assertTrue("MTH_U 嘟嘴形变应激活", out[8] > 0.8f)
    }

    @Test
    fun `joy and smug activate cute anime fang`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "JOY", EmotionType.JOY, 0f, 0f)
        assertTrue("JOY 露齿笑应露出小虎牙", out[9] > 0.6f)
        rig.fillWeights(out, "SMUG", EmotionType.CONFIDENT, 0f, 0f)
        assertTrue("SMUG 得意应完全露出小虎牙", out[9] > 0.9f)
    }

    @Test
    fun `blink closes eyes`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 1f, 0f)
        assertEquals(1f, out[3], 1e-4f)
    }

    @Test
    fun `mouth open drives vowel morp`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 0f, 0.8f)
        assertTrue("MTH_A 应随口型张开", out[6] > 0.6f)
    }

    @Test
    fun `unknown expression falls back by emotion`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "NOT_EXIST", EmotionType.NEUTRAL, 0f, 0f)
        assertTrue(out[5] > 0f) // MTH_Neutral 兜底
    }
}