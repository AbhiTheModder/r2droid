package top.wsdx233.r2droid.feature.debug.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R2DebugCommandRunnerTest {
    private val runner = R2DebugCommandRunner()

    @Test
    fun parseJsonArray_stripsFridaPrefix() {
        val parsed: JSONArray = runner.parseJsonArray("r2frida> [{\"base\":\"0x1000\",\"size\":16}]\n")

        assertEquals(1, parsed.length())
        assertEquals("0x1000", parsed.getJSONObject(0).getString("base"))
    }

    @Test
    fun parseJsonObject_stripsLogsAroundPayload() {
        val parsed: JSONObject = runner.parseJsonObject("log before\n{\"pc\":4096,\"sp\":8192}\nlog after")

        assertEquals(4096L, parsed.getLong("pc"))
        assertEquals(8192L, parsed.getLong("sp"))
    }

    @Test
    fun parseFridaRegisterOutput_supportsR2FridaTextBlocks() {
        val parsed = runner.parseFridaRegisterOutput(
            """
            tid 123 waiting
              pc : 0x0000000100001234
              sp : 0x000000016fdff000
              x0 : 0x2a
            """.trimIndent()
        )

        assertEquals(0x100001234L, parsed.getLong("pc"))
        assertEquals(0x16fdff000L, parsed.getLong("sp"))
        assertEquals(0x2aL, parsed.getLong("x0"))
    }

    @Test
    fun parseLongFlexible_supportsHexDecimalAndCommas() {
        assertEquals(0x1234L, runner.parseLongFlexible("0x1234"))
        assertEquals(1234L, runner.parseLongFlexible("1234"))
        assertEquals(0xabL, runner.parseLongFlexible("0xab,"))
    }

    @Test
    fun parseAddress_usesFirstOutputLine() {
        assertEquals(0x401000L, runner.parseAddress("0x401000\nextra output"))
    }

    @Test
    fun registerRoleForName_detectsImportantRoles() {
        assertEquals(RegisterRole.PC, registerRoleForName("rip"))
        assertEquals(RegisterRole.SP, registerRoleForName("rsp"))
        assertEquals(RegisterRole.BP, registerRoleForName("fp"))
        assertEquals(RegisterRole.FLAGS, registerRoleForName("eflags"))
        assertEquals(RegisterRole.VECTOR, registerRoleForName("xmm0"))
        assertEquals(RegisterRole.GENERAL, registerRoleForName("rax"))
    }

    @Test
    fun parseBreakpoints_supportsCommonAddressFieldsAndDeduplicates() {
        val parsed = runner.parseBreakpoints(
            """
            [
              {"addr":"0x1000","enabled":true,"temporary":true,"cond":"eax==1","hits":3,"name":"bp0"},
              {"offset":8192,"enabled":false,"condition":"x0==0","hit":2,"id":"bp1"},
              {"vaddr":"0x1000","enabled":true}
            ]
            """.trimIndent()
        )

        assertEquals(2, parsed.size)
        assertEquals(0x1000L, parsed[0].address)
        assertTrue(parsed[0].temporary)
        assertEquals("eax==1", parsed[0].condition)
        assertEquals(3, parsed[0].hitCount)
        assertEquals("bp0", parsed[0].backendId)
        assertEquals(8192L, parsed[1].address)
        assertEquals(false, parsed[1].enabled)
        assertEquals("x0==0", parsed[1].condition)
        assertEquals(2, parsed[1].hitCount)
        assertEquals("bp1", parsed[1].backendId)
    }

    @Test
    fun validateCommandOutput_detectsCommonFailures() {
        val cases = listOf(
            "error: command failed",
            "unknown command: ds",
            "invalid command",
            "Cannot open file",
            "permission denied",
            "not in debug mode"
        )

        cases.forEach { output ->
            val result = runCatching { runner.validateCommandOutput("cmd", output) }
            assertTrue("Expected failure for: $output", result.isFailure)
        }
    }

    @Test
    fun validateCommandOutput_acceptsNormalOutput() {
        runner.validateCommandOutput("cmd", "0x1000\n")
    }

    @Test
    fun parseJsonArray_rejectsMissingPayload() {
        val result = runCatching { runner.parseJsonArray("not json") }
        assertTrue(result.isFailure)
    }

    @Test
    fun toFridaCommand_addsPrefixAndTrimsLeadingWhitespace() {
        assertEquals(":drj", runner.toFridaCommand("drj"))
        assertEquals(":dbj", runner.toFridaCommand("  :dbj"))
    }

    @Test
    fun isFridaCommand_detectsColonPrefixedR2FridaCommands() {
        assertTrue(runner.isFridaCommand(":drj"))
        assertTrue(runner.isFridaCommand("  :dmj"))
        assertTrue(!runner.isFridaCommand("drj"))
    }
}
