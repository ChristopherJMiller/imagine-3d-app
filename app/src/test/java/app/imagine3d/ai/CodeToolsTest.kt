package app.imagine3d.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CodeToolsTest {
    private val sample = """
        include <imagine.scad>
        // Plan: hook over a 19 mm door.

        door_t = 19;   // measured door thickness
        width  = 25;   // hook width
        count = 3;
        module helper() {
            inner = 5;
        }
        over_door_hook(door_t = door_t, width = width);
    """.trimIndent()

    @Test fun extractsLongestFencedBlock() {
        val reply = "Here:\n```openscad\ncube(1);\n```\nand\n```openscad\ncube([1,2,3]);\nsphere(2);\n```\n"
        assertEquals("cube([1,2,3]);\nsphere(2);\n", CodeTools.extractCode(reply))
    }

    @Test fun extractsUnterminatedBlock() {
        assertEquals("cube(1);\n", CodeTools.extractCode("```openscad\ncube(1);\n"))
    }

    @Test fun streamingViewHidesFences() {
        assertEquals("", CodeTools.streamingView("```open"))
        assertEquals("include <imagine.scad>\ncu", CodeTools.streamingView("```openscad\ninclude <imagine.scad>\ncu"))
        assertEquals("cube(1);\n", CodeTools.streamingView("```openscad\ncube(1);\n```"))
    }

    @Test fun detectsClosedBlock() {
        assertFalse(CodeTools.codeBlockClosed("```openscad\ncube(1);\n"))
        assertTrue(CodeTools.codeBlockClosed("```openscad\ncube(1);\n```"))
        assertFalse(CodeTools.codeBlockClosed("```"))
    }

    @Test fun extractsNameOnlyOnceTheLineIsComplete() {
        assertEquals(null, CodeTools.extractName("```openscad\ninclude <imagine.scad>\n// Name: Cabinet Do"))
        assertEquals("Cabinet Door Towel Hook", CodeTools.extractName("```openscad\ninclude <imagine.scad>\n// Name: Cabinet Door Towel Hook\n// Plan"))
        assertEquals("A Very Long Name That Keeps", CodeTools.extractName("// Name: \"A Very Long Name That Keeps Going On.\"\n"))
        assertEquals(null, CodeTools.extractName("// Name:   \n"))
    }

    @Test fun parsesTopLevelParamsOnly() {
        val params = CodeTools.parseParams(sample)
        assertEquals(listOf("door_t", "width", "count"), params.map { it.name })
        assertEquals("measured door thickness", params[0].comment)
        assertEquals(1.0, params.first { it.name == "count" }.step, 0.0)
    }

    @Test fun appliesOverrides() {
        val out = CodeTools.applyOverrides(sample, mapOf("door_t" to 21.5, "inner" to 9.0))
        assertTrue(out.contains("door_t = 21.5;   // measured door thickness"))
        assertTrue("assignments inside modules are left alone", out.contains("inner = 5;"))
    }

    @Test fun formatsLikePythonG() {
        assertEquals("19", PromptAssets.formatMm(19.0))
        assertEquals("1.5", PromptAssets.formatMm(1.5))
        assertEquals("12.3", PromptAssets.formatMm(12.34))
    }

    @Test fun countsSeparatePieces() {
        // two triangles sharing an edge + one far away triangle = 2 pieces
        val tris = listOf(
            listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
            listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f)),
            listOf(floatArrayOf(10f, 10f, 5f), floatArrayOf(11f, 10f, 5f), floatArrayOf(10f, 11f, 5f)),
        )
        val buf = ByteBuffer.allocate(84 + tris.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(tris.size)
        for (t in tris) { repeat(3) { buf.putFloat(0f) }; t.forEach { v -> v.forEach(buf::putFloat) }; buf.putShort(0) }
        val check = StlTools.analyze(buf.array())!!
        assertEquals(2, check.components)
        assertEquals(11.0, check.size[0], 1e-6)
        assertEquals(5.0, check.size[2], 1e-6)
    }
}
