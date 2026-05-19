package top.wsdx233.r2droid.feature.debug.data

import org.json.JSONObject
import top.wsdx233.r2droid.util.R2PipeManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebuggerRepository @Inject constructor(
    private val runner: R2DebugCommandRunner,
    private val esilBackend: EsilDebuggerBackend,
    private val nativeBackend: NativeR2DebuggerBackend,
    private val fridaBackend: FridaDebuggerBackend,
    private val gdbRemoteBackend: GdbRemoteDebuggerBackend
) {
    private var activeBackend: DebuggerBackend = esilBackend
    private var activeConfig: DebugSessionConfig = DebugSessionConfig(DebugBackend.ESIL)
    private var lastSnapshot: DebugStateSnapshot? = null

    private fun backendFor(type: DebugBackend): DebuggerBackend = when (type) {
        DebugBackend.ESIL -> esilBackend
        DebugBackend.NATIVE_GDB -> nativeBackend
        DebugBackend.FRIDA -> fridaBackend
        DebugBackend.GDB_REMOTE -> gdbRemoteBackend
    }

    private fun validateBackendSession(type: DebugBackend): Result<Unit> {
        if (type == DebugBackend.FRIDA && !R2PipeManager.isR2FridaSession) {
            return Result.failure(IllegalStateException(R2DebugCommandRunner.FRIDA_SESSION_REQUIRED_MESSAGE))
        }
        if (type != DebugBackend.FRIDA && R2PipeManager.isR2FridaSession) {
            return Result.failure(
                IllegalStateException("${type.displayName} debug backend is not available in an r2frida session; use Frida backend")
            )
        }
        return Result.success(Unit)
    }

    private fun validatedBackendFor(type: DebugBackend): Result<DebuggerBackend> {
        return validateBackendSession(type).map { backendFor(type) }
    }

    private fun snapshotToJsonRegisters(snapshot: DebugStateSnapshot): JSONObject {
        val json = JSONObject()
        snapshot.registers.forEach { register ->
            json.put(register.name, register.value)
        }
        return json
    }

    suspend fun probe(backend: DebugBackend): Result<DebugCapabilities> {
        return validatedBackendFor(backend).fold(
            onSuccess = { it.probe() },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun start(config: DebugSessionConfig): Result<DebugStateSnapshot> {
        val target = validatedBackendFor(config.backend).getOrElse { return Result.failure(it) }
        activeBackend = target
        activeConfig = config
        return activeBackend.start(config).onSuccess { lastSnapshot = it }
    }

    suspend fun reset(config: DebugSessionConfig = activeConfig): Result<DebugStateSnapshot> {
        val target = validatedBackendFor(config.backend).getOrElse { return Result.failure(it) }
        activeBackend = target
        activeConfig = config
        return activeBackend.reset(config).onSuccess { lastSnapshot = it }
    }

    suspend fun stop(): Result<Unit> {
        return activeBackend.stop().onSuccess { lastSnapshot = null }
    }

    suspend fun refresh(): Result<DebugStateSnapshot> {
        return activeBackend.refresh().onSuccess { lastSnapshot = it }
    }

    suspend fun stepInto(): Result<DebugStateSnapshot> {
        return activeBackend.stepInto().onSuccess { lastSnapshot = it }
    }

    suspend fun stepOver(): Result<DebugStateSnapshot> {
        return activeBackend.stepOver().onSuccess { lastSnapshot = it }
    }

    suspend fun stepOut(): Result<DebugStateSnapshot> {
        return activeBackend.stepOut().onSuccess { lastSnapshot = it }
    }

    suspend fun continueRun(): Result<DebugStateSnapshot> {
        val timeoutMs = activeConfig.esilTimeoutMs.takeIf { activeConfig.backend == DebugBackend.ESIL }
        val result = if (timeoutMs != null && timeoutMs > 0L) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                if (activeConfig.backend == DebugBackend.ESIL && activeConfig.maxEsilSteps > 0) {
                    esilBackend.continueRun(activeConfig.maxEsilSteps)
                } else {
                    activeBackend.continueRun()
                }
            } ?: Result.failure(java.util.concurrent.TimeoutException("ESIL continue timed out after ${timeoutMs}ms"))
        } else if (activeConfig.backend == DebugBackend.ESIL && activeConfig.maxEsilSteps > 0) {
            esilBackend.continueRun(activeConfig.maxEsilSteps)
        } else {
            activeBackend.continueRun()
        }
        return result.onSuccess { lastSnapshot = it }
    }

    suspend fun pause(): Result<DebugStateSnapshot> {
        return activeBackend.pause().onSuccess { lastSnapshot = it }
    }

    suspend fun setBreakpoint(breakpoint: DebugBreakpoint): Result<DebugBreakpoint> {
        return activeBackend.setBreakpoint(breakpoint)
    }

    suspend fun removeBreakpoint(address: Long): Result<Unit> {
        return activeBackend.removeBreakpoint(address)
    }

    suspend fun listBreakpoints(): Result<List<DebugBreakpoint>> {
        return activeBackend.listBreakpoints()
    }

    suspend fun readRegisters(): Result<List<DebugRegister>> {
        return activeBackend.readRegisters()
    }

    suspend fun readStack(count: Int = 16): Result<List<DebugStackEntry>> {
        return activeBackend.readStack(count)
    }

    suspend fun writeRegister(name: String, value: Long): Result<Unit> {
        return activeBackend.writeRegister(name, value)
    }

    suspend fun readMemory(address: Long, size: Int): Result<ByteArray> {
        return activeBackend.readMemory(address, size)
    }

    suspend fun getLastSnapshot(): DebugStateSnapshot? = lastSnapshot

    // === Compatibility API used by DisasmViewModel. These wrappers allow phased migration. ===

    suspend fun startDebugging(backend: DebugBackend): Result<String> {
        val result = start(DebugSessionConfig(backend = backend))
        return result.map { it.message ?: it.stopReason.message ?: "${backend.displayName} debug started" }
    }

    suspend fun resetDebugging(backend: DebugBackend, startAddress: Long? = null): Result<String> {
        val result = reset(activeConfig.copy(backend = backend, startAddress = startAddress))
        return result.map { it.message ?: it.stopReason.message ?: "${backend.displayName} debug reset" }
    }

    suspend fun stopDebugging(backend: DebugBackend): Result<String> {
        val target = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        activeBackend = target
        return target.stop().map { "${backend.displayName} debug stopped" }
            .onSuccess { lastSnapshot = null }
    }

    suspend fun getBreakpoints(): Result<Set<Long>> {
        return activeBackend.listBreakpoints().map { it.map { bp -> bp.address }.toSet() }
    }

    suspend fun toggleBreakpoint(addr: Long, isAdd: Boolean): Result<String> {
        return if (isAdd) {
            activeBackend.setBreakpoint(DebugBreakpoint(address = addr)).map { "Breakpoint set at 0x${addr.toString(16)}" }
        } else {
            activeBackend.removeBreakpoint(addr).map { "Breakpoint removed at 0x${addr.toString(16)}" }
        }
    }

    suspend fun stepInto(backend: DebugBackend): Result<String> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return stepInto().map { it.stopReason.message ?: "Step into" }
    }

    suspend fun stepOver(backend: DebugBackend): Result<String> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return stepOver().map { it.stopReason.message ?: "Step over" }
    }

    suspend fun continueExecution(backend: DebugBackend): Result<String> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return continueRun().map { it.stopReason.message ?: "Execution stopped" }
    }

    suspend fun runToCursor(backend: DebugBackend, address: Long): Result<String> = runCatching {
        activeBackend = validatedBackendFor(backend).getOrThrow()
        activeBackend.setBreakpoint(DebugBreakpoint(address = address, temporary = true)).getOrThrow()
        val result = activeBackend.continueRun()
        activeBackend.removeBreakpoint(address).getOrNull()
        val snapshot = result.getOrThrow()
        lastSnapshot = snapshot
        snapshot.stopReason.message ?: "Run to cursor stopped at 0x${address.toString(16)}"
    }

    suspend fun setProgramCounter(backend: DebugBackend, address: Long): Result<String> = runCatching {
        activeBackend = validatedBackendFor(backend).getOrThrow()
        val registers = activeBackend.readRegisters().getOrThrow()
        val pcName = registers.firstOrNull { it.role == RegisterRole.PC }?.name ?: "pc"
        activeBackend.writeRegister(pcName, address).getOrThrow()
        runner.executeForBackend(backend, "s $address").getOrThrow()
        lastSnapshot = activeBackend.refresh().getOrNull()
        "PC set to 0x${address.toString(16)}"
    }

    suspend fun getStack(backend: DebugBackend, count: Int = 16): Result<List<DebugStackEntry>> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return activeBackend.readStack(count)
    }

    suspend fun getStackPointer(backend: DebugBackend): Result<Long> = runCatching {
        activeBackend = validatedBackendFor(backend).getOrThrow()
        val registers = activeBackend.readRegisters().getOrThrow()
        registers.firstOrNull { it.role == RegisterRole.SP }?.value
            ?: registers.firstOrNull { it.name.equals("sp", true) || it.name.equals("rsp", true) || it.name.equals("esp", true) }?.value
            ?: registers.firstOrNull { it.name.lowercase().endsWith("sp") }?.value
            ?: throw IllegalStateException("Stack pointer is unavailable")
    }

    suspend fun readMemory(backend: DebugBackend, address: Long, size: Int): Result<ByteArray> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return activeBackend.readMemory(address, size)
    }

    suspend fun getRegisters(backend: DebugBackend): Result<JSONObject> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        return activeBackend.readRegisters().map { registers ->
            JSONObject().also { obj -> registers.forEach { obj.put(it.name, it.value) } }
        }
    }

    suspend fun getCurrentPC(backend: DebugBackend): Result<Long> {
        activeBackend = validatedBackendFor(backend).getOrElse { return Result.failure(it) }
        val refreshResult = activeBackend.refresh()
        val snapshot = refreshResult.getOrNull()
        if (snapshot != null) {
            lastSnapshot = snapshot
            return snapshot.pc?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Unable to determine program counter"))
        }

        val registers = getRegisters(backend).getOrElse {
            return Result.failure(refreshResult.exceptionOrNull() ?: it)
        }
        val keys = registers.keys().asSequence().toList()
        val pc = keys.firstOrNull { key ->
            key.equals("pc", true) || key.equals("rip", true) || key.equals("eip", true) || key.equals("ip", true)
        }?.let { key -> runner.parseLongFlexible(registers.opt(key)) }
            ?: keys.firstOrNull { it.lowercase().endsWith("ip") }?.let { key -> runner.parseLongFlexible(registers.opt(key)) }

        return pc?.let { Result.success(it) }
            ?: Result.failure(refreshResult.exceptionOrNull() ?: IllegalStateException("Unable to determine program counter"))
    }

    fun snapshotRegistersAsJson(): JSONObject? = lastSnapshot?.let { snapshotToJsonRegisters(it) }
}
