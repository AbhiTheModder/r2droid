package top.wsdx233.r2droid.feature.debug.data

import org.json.JSONObject
import java.util.Locale

abstract class BaseR2DebuggerBackend(
    protected val runner: R2DebugCommandRunner
) : DebuggerBackend {

    protected var lastRegisters: Map<String, Long> = emptyMap()

    protected abstract val registerCommands: List<String>
    protected abstract val breakpointListCommand: String
    protected abstract val seekCommand: String

    override suspend fun refresh(): Result<DebugStateSnapshot> = runCatching {
        val registers = readRegisters().getOrThrow()
        val pc = extractProgramCounter(registers) ?: readSeekAddress().getOrNull()
        val breakpoints = listBreakpoints().getOrDefault(emptyList())
        DebugStateSnapshot(
            backend = type,
            runState = DebugRunState.SUSPENDED,
            pc = pc,
            registers = registers,
            breakpoints = breakpoints,
            stopReason = DebugStopReason(DebugStopReasonType.UNKNOWN, address = pc)
        )
    }

    override suspend fun listBreakpoints(): Result<List<DebugBreakpoint>> = runCatching {
        val raw = runner.executeForBackend(type, breakpointListCommand).getOrThrow()
        runner.parseBreakpoints(raw)
    }

    override suspend fun readRegisters(): Result<List<DebugRegister>> = runCatching {
        var lastError: Throwable? = null
        for (command in registerCommands) {
            val output = runner.executeForBackend(type, command).getOrElse {
                lastError = it
                continue
            }
            val obj = try {
                if (type == DebugBackend.FRIDA) {
                    runner.parseFridaRegisterOutput(output)
                } else {
                    runner.parseJsonObject(output)
                }
            } catch (e: Exception) {
                lastError = e
                continue
            }
            return@runCatching parseRegisters(obj)
        }
        throw lastError ?: IllegalStateException("Unable to read register state")
    }

    protected suspend fun readSeekAddress(): Result<Long> = runCatching {
        runner.parseAddress(runner.executeForBackend(type, seekCommand).getOrThrow())
            ?: throw IllegalStateException("Unable to determine current seek address")
    }

    protected fun parseRegisters(registers: JSONObject): List<DebugRegister> {
        val current = mutableMapOf<String, Long>()
        val keys = registers.keys().asSequence().toList()
        keys.forEach { key ->
            runner.parseLongFlexible(registers.opt(key))?.let { value ->
                current[key] = value
            }
        }

        val parsed = current.map { (name, value) ->
            DebugRegister(
                name = name,
                value = value,
                previousValue = lastRegisters[name],
                role = registerRoleForName(name)
            )
        }.sortedWith(registerComparator())

        lastRegisters = current
        return parsed
    }

    override suspend fun readMemory(address: Long, size: Int): Result<ByteArray> = runCatching {
        val safeSize = size.coerceIn(1, 4096)
        val raw = runner.executeForBackend(type, "pxj $safeSize @ $address").getOrThrow()
        val arr = runner.parseJsonArray(raw)
        ByteArray(arr.length()) { index ->
            (runner.parseLongFlexible(arr.opt(index)) ?: 0L).toInt().toByte()
        }
    }

    override suspend fun readStack(count: Int): Result<List<DebugStackEntry>> = runCatching {
        val registers = readRegisters().getOrThrow()
        val sp = extractStackPointer(registers)
            ?: throw IllegalStateException("Stack pointer is unavailable")
        val bytes = (count.coerceAtLeast(1) * 8).coerceAtMost(512)
        val raw = runner.executeForBackend(type, "pxqj $bytes @ $sp").getOrThrow()
        val arr = runner.parseJsonArray(raw)
        val entries = mutableListOf<DebugStackEntry>()
        for (i in 0 until arr.length()) {
            runner.parseLongFlexible(arr.opt(i))?.let { value ->
                entries.add(DebugStackEntry(address = sp + i * 8L, value = value))
            }
        }
        entries
    }

    protected fun extractProgramCounter(registers: List<DebugRegister>): Long? {
        registers.firstOrNull { it.role == RegisterRole.PC }?.let { return it.value }
        val pcNames = listOf("PC", "pc", "rip", "eip", "ip")
        pcNames.forEach { name ->
            registers.firstOrNull { it.name == name }?.let { return it.value }
        }
        registers.firstOrNull { it.name.lowercase(Locale.ROOT).endsWith("ip") }?.let { return it.value }
        return null
    }

    protected fun extractStackPointer(registers: List<DebugRegister>): Long? {
        registers.firstOrNull { it.role == RegisterRole.SP }?.let { return it.value }
        val spNames = listOf("SP", "sp", "rsp", "esp")
        spNames.forEach { name ->
            registers.firstOrNull { it.name == name }?.let { return it.value }
        }
        registers.firstOrNull { it.name.lowercase(Locale.ROOT).endsWith("sp") }?.let { return it.value }
        return null
    }

    private fun registerComparator(): Comparator<DebugRegister> = compareBy<DebugRegister> {
        when (it.role) {
            RegisterRole.PC -> 0
            RegisterRole.SP -> 1
            RegisterRole.BP -> 2
            RegisterRole.FLAGS -> 3
            RegisterRole.GENERAL -> 4
            RegisterRole.SEGMENT -> 5
            RegisterRole.FLOAT -> 6
            RegisterRole.VECTOR -> 7
        }
    }.thenBy { it.name.lowercase(Locale.ROOT) }
}
