package com.persona.ar.character.animation

import com.persona.ar.data.model.EmotionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRigTest {

    private fun faceRig(): FaceRig = FaceRig(arrayOf(
        "Face.M_F00_000_00_Fcl_ALL_Joy",
        "Face.M_F00_000_00_Fcl_BRW_Joy",
        "Face.M_F00_000_00_Fcl_BRW_Sorrow",
        "Face.M_F00_000_00_Fcl_BRW_Angry",
        "Face.M_F00_000_00_Fcl_EYE_Joy",
        "Face.M_F00_000_00_Fcl_EYE_Close",
        "Face.M_F00_000_00_Fcl_EYE_Angry",
        "Face.M_F00_000_00_Fcl_MTH_Joy",
        "Face.M_F00_000_00_Fcl_MTH_Fun",
        "Face.M_F00_000_00_Fcl_MTH_Neutral",
        "Face.M_F00_000_00_Fcl_MTH_A",
        "Face.M_F00_000_00_Fcl_MTH_Up",
        "Face.M_F00_000_00_Fcl_ALL_Angry",
        "Face.M_F00_000_00_Fcl_MTH_U",
        "Face.M_F00_000_00_Fcl_HA_Fung1",
        "Face.M_F00_000_00_Fcl_ALL_Surprised",
        "Face.M_F00_000_00_Fcl_MTH_O"
    ))

    @Test
    fun `smile sets clean non-stacked joy morph weights`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 0f, 0f)
        assertEquals("SMILE 不应叠加 ALL_Joy 复合形变", 0f, out[0], 1e-4f)
        assertTrue("BRW_Joy 应被激活", out[1] > 0.2f)
        assertTrue("EYE_Joy 应被激活", out[4] > 0.2f)
        assertTrue("MTH_Up 应形成温和微笑", out[11] > 0.3f)
    }

    @Test
    fun `pout drives duck mouth and angry brow without double stacking ALL_Angry`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "POUT", EmotionType.ANGRY, 0f, 0f)
        assertEquals("POUT 不应叠加 ALL_Angry", 0f, out[12], 1e-4f)
        assertTrue("BRW_Angry 应有鲜明怒眉", out[3] > 0.8f)
        assertTrue("MTH_U 嘟嘴形变应激活", out[13] > 0.7f)
    }

    @Test
    fun `surprised keeps jaw opening natural without double stacking`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SURPRISED", EmotionType.SURPRISED, 0f, 0f)
        assertEquals("SURPRISED 不应叠加 ALL_Surprised 防止张嘴脱臼", 0f, out[15], 1e-4f)
        assertTrue("MTH_O 应适度柔和张开", out[16] in 0.2f..0.6f)
    }

    @Test
    fun `joy and smug activate cute anime fang`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "JOY", EmotionType.JOY, 0f, 0f)
        assertTrue("JOY 露齿笑应露出小虎牙", out[14] > 0.5f)
        rig.fillWeights(out, "SMUG", EmotionType.CONFIDENT, 0f, 0f)
        assertTrue("SMUG 得意应完全露出小虎牙", out[14] > 0.9f)
    }

    @Test
    fun `blink closes eyes`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 1f, 0f)
        assertEquals(1f, out[5], 1e-4f)
    }

    @Test
    fun `mouth open drives vowel morp`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "SMILE", EmotionType.NEUTRAL, 0f, 0.8f)
        assertTrue("MTH_A 应随口型张开", out[10] > 0.6f)
    }

    @Test
    fun `unknown expression falls back by emotion`() {
        val rig = faceRig()
        val out = FloatArray(rig.morphCount)
        rig.fillWeights(out, "NOT_EXIST", EmotionType.NEUTRAL, 0f, 0f)
        assertTrue(out[9] > 0f) // MTH_Neutral 兜底
    }
}