package top.wsdx233.r2droid.feature.debug.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebuggerBackendProbeTest {
    @Test
    fun esilProbe_reportsSimulationCapabilities() = runTest {
        val capabilities = EsilDebuggerBackend(R2DebugCommandRunner()).probe().getOrThrow()

        assertTrue(capabilities.canStepInto)
        assertTrue(capabilities.canStepOver)
        assertTrue(capabilities.canContinue)
        assertTrue(capabilities.canPause)
        assertTrue(capabilities.canEditRegisters)
        assertTrue(capabilities.canReadMemory)
        assertTrue(capabilities.canUseBreakpoints)
        assertTrue(capabilities.canTrace)
        assertFalse(capabilities.canStepOut)
    }

    @Test
    fun nativeProbe_reportsNativeDebuggerCapabilities() = runTest {
        val capabilities = NativeR2DebuggerBackend(null, R2DebugCommandRunner())
            .probe()
            .getOrThrow()

        assertNativeLikeCapabilities(capabilities, hardwareBreakpoints = true)
    }

    @Test
    fun gdbRemoteProbe_reportsRemoteCapabilities() = runTest {
        val capabilities = GdbRemoteDebuggerBackend(null, R2DebugCommandRunner())
            .probe()
            .getOrThrow()

        assertNativeLikeCapabilities(capabilities, hardwareBreakpoints = false)
    }

    private fun assertNativeLikeCapabilities(capabilities: DebugCapabilities, hardwareBreakpoints: Boolean) {
        assertTrue(capabilities.canStepInto)
        assertTrue(capabilities.canStepOver)
        assertTrue(capabilities.canStepOut)
        assertTrue(capabilities.canContinue)
        assertTrue(capabilities.canPause)
        assertTrue(capabilities.canEditRegisters)
        assertTrue(capabilities.canReadMemory)
        assertTrue(capabilities.canWriteMemory)
        assertTrue(capabilities.canUseBreakpoints)
        assertTrue(capabilities.canUseHardwareBreakpoints == hardwareBreakpoints)
        assertTrue(capabilities.canListThreads)
        assertTrue(capabilities.canListFrames)
    }

    @Test
    fun defaultBackendMethodsReturnUnsupportedForOptionalFeatures() = runTest {
        val backend = object : DebuggerBackend {
            override val type = DebugBackend.ESIL
            override suspend fun probe() = Result.success(DebugCapabilities())
            override suspend fun start(config: DebugSessionConfig) = error("not used")
            override suspend fun stop() = Result.success(Unit)
            override suspend fun refresh() = error("not used")
            override suspend fun stepInto() = error("not used")
            override suspend fun stepOver() = error("not used")
            override suspend fun continueRun() = error("not used")
            override suspend fun pause() = error("not used")
            override suspend fun setBreakpoint(breakpoint: DebugBreakpoint) = Result.success(breakpoint)
            override suspend fun removeBreakpoint(address: Long) = Result.success(Unit)
            override suspend fun listBreakpoints() = Result.success(emptyList<DebugBreakpoint>())
            override suspend fun readRegisters() = Result.success(emptyList<DebugRegister>())
        }

        assertTrue(backend.stepOut().isFailure)
        assertTrue(backend.writeRegister("pc", 1).isFailure)
        assertTrue(backend.readMemory(0, 1).isFailure)
        assertTrue(backend.writeMemory(0, byteArrayOf(1)).isFailure)
        assertTrue(backend.listThreads().getOrThrow().isEmpty())
        assertTrue(backend.listFrames().getOrThrow().isEmpty())
        assertTrue(backend.readStack().getOrThrow().isEmpty())
    }

}
