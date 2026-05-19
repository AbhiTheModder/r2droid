package top.wsdx233.r2droid.feature.debug.data

import javax.inject.Inject

class EsilDebuggerBackend @Inject constructor(
    runner: R2DebugCommandRunner
) : BaseR2DebuggerBackend(runner) {

    override val type: DebugBackend = DebugBackend.ESIL
    override val registerCommands: List<String> = listOf("aerj", "arj")
    override val breakpointListCommand: String = "dbj"
    override val seekCommand: String = "s"

    override suspend fun probe(): Result<DebugCapabilities> = Result.success(
        DebugCapabilities(
            canStepInto = true,
            canStepOver = true,
            canStepOut = false,
            canContinue = true,
            canPause = true,
            canEditRegisters = true,
            canReadMemory = true,
            canWriteMemory = false,
            canUseBreakpoints = true,
            canTrace = true
        )
    )

    override suspend fun start(config: DebugSessionConfig): Result<DebugStateSnapshot> = runCatching {
        runner.execute("aei").getOrThrow()
        runner.execute("aeim").getOrThrow()
        runner.execute("aeip").getOrThrow()

        val start = config.startAddress
            ?: if (config.startAtCurrentSeek) readSeekAddress().getOrNull() else null

        if (start != null) {
            runner.execute("s $start").getOrThrow()
            setProgramCounter(start)
        }
        initializeStack(config)

        refresh().getOrThrow().copy(
            runState = DebugRunState.SUSPENDED,
            stopReason = DebugStopReason(DebugStopReasonType.STARTED, "ESIL initialized", address = start)
        )
    }

    override suspend fun reset(config: DebugSessionConfig): Result<DebugStateSnapshot> = runCatching {
        runner.execute("aei-").getOrNull()
        lastRegisters = emptyMap()
        start(config).getOrThrow().copy(
            stopReason = DebugStopReason(DebugStopReasonType.STARTED, "ESIL reset", address = config.startAddress)
        )
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        runner.execute("aei-").getOrThrow()
        lastRegisters = emptyMap()
    }

    override suspend fun stepInto(): Result<DebugStateSnapshot> = runCatching {
        runner.execute("aes").getOrThrow()
        refresh().getOrThrow().copy(
            stopReason = DebugStopReason(DebugStopReasonType.STEP, "Step into")
        )
    }

    override suspend fun stepOver(): Result<DebugStateSnapshot> = runCatching {
        runner.execute("aeso").getOrThrow()
        refresh().getOrThrow().copy(
            stopReason = DebugStopReason(DebugStopReasonType.STEP, "Step over")
        )
    }

    override suspend fun continueRun(): Result<DebugStateSnapshot> = runCatching {
        val output = runner.execute("aec").getOrThrow()
        refresh().getOrThrow().copy(stopReason = parseContinueStopReason(output))
    }

    suspend fun continueRun(maxSteps: Int): Result<DebugStateSnapshot> = runCatching {
        if (maxSteps <= 0) {
            return continueRun()
        }
        var lastReason = DebugStopReason(DebugStopReasonType.UNKNOWN, "ESIL max step limit reached ($maxSteps)")
        repeat(maxSteps) { index ->
            val output = runner.execute("aes").getOrThrow()
            val parsedReason = parseContinueStopReason(output)
            if (parsedReason.type != DebugStopReasonType.UNKNOWN || output.isNotBlank()) {
                lastReason = parsedReason.copy(
                    message = parsedReason.message ?: output.take(240)
                )
                return@runCatching refresh().getOrThrow().copy(stopReason = lastReason)
            }
            val breakpointHit = isAtBreakpoint()
            if (breakpointHit != null) {
                lastReason = DebugStopReason(
                    type = DebugStopReasonType.BREAKPOINT,
                    message = "ESIL breakpoint at 0x${breakpointHit.toString(16)} after ${index + 1} steps",
                    address = breakpointHit
                )
                return@runCatching refresh().getOrThrow().copy(stopReason = lastReason)
            }
        }
        refresh().getOrThrow().copy(stopReason = lastReason)
    }

    override suspend fun pause(): Result<DebugStateSnapshot> = refresh().mapCatching {
        it.copy(stopReason = DebugStopReason(DebugStopReasonType.MANUAL_PAUSE, "ESIL execution interrupted"))
    }

    override suspend fun setBreakpoint(breakpoint: DebugBreakpoint): Result<DebugBreakpoint> = runCatching {
        runner.execute("db ${breakpoint.address}").getOrThrow()
        breakpoint
    }

    override suspend fun removeBreakpoint(address: Long): Result<Unit> = runCatching {
        runner.execute("db- $address").getOrThrow()
    }

    override suspend fun writeRegister(name: String, value: Long): Result<Unit> = runCatching {
        runner.execute("aer $name=$value").getOrThrow()
    }

    private suspend fun setProgramCounter(address: Long) {
        val registers = readRegisters().getOrNull().orEmpty()
        val pcName = registers.firstOrNull { it.role == RegisterRole.PC }?.name ?: "pc"
        runner.execute("aer $pcName=$address").getOrNull()
    }

    private suspend fun initializeStack(config: DebugSessionConfig) {
        val stackBase = config.stackAddress ?: return
        val stackSize = config.stackSize.coerceAtLeast(0x1000L)
        val stackPointer = stackBase + stackSize - 16L
        runner.execute("aeim $stackBase $stackSize").getOrNull()
        val registers = readRegisters().getOrNull().orEmpty()
        val spName = registers.firstOrNull { it.role == RegisterRole.SP }?.name ?: "sp"
        runner.execute("aer $spName=$stackPointer").getOrNull()
    }

    private fun parseContinueStopReason(output: String): DebugStopReason {
        val lower = output.lowercase()
        return when {
            lower.contains("invalid") || lower.contains("unsupported") || lower.contains("trap") -> {
                DebugStopReason(DebugStopReasonType.ESIL_ERROR, output.ifBlank { "ESIL stopped with an error" }.take(240))
            }
            lower.contains("break") || lower.contains("breakpoint") -> {
                DebugStopReason(DebugStopReasonType.BREAKPOINT, output.ifBlank { "ESIL breakpoint" }.take(240))
            }
            lower.contains("segfault") || lower.contains("fault") || lower.contains("exception") -> {
                DebugStopReason(DebugStopReasonType.EXCEPTION, output.take(240))
            }
            else -> DebugStopReason(DebugStopReasonType.UNKNOWN, output.ifBlank { "ESIL continue stopped" }.take(240))
        }
    }

    private suspend fun isAtBreakpoint(): Long? {
        val pc = refresh().getOrNull()?.pc ?: return null
        return listBreakpoints().getOrNull()
            ?.firstOrNull { it.enabled && it.address == pc }
            ?.address
    }
}
