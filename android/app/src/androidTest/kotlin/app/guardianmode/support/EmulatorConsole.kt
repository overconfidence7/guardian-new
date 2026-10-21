package app.guardianmode.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Android emulator's console, reached from inside the emulator through the host
 * loopback alias 10.0.2.2. Lets a test place real incoming calls (`gsm call`), the same
 * thing `adb emu gsm call` does from the host, so screening, phone state and the
 * tripwires run through Android's real telephony stack.
 *
 * CI writes an empty ~/.emulator_console_auth_token before booting the emulator, which
 * turns console auth off. Pass -e consoleToken <token> to the runner to use a real one.
 */
object EmulatorConsole {
    private const val HOST = "10.0.2.2"
    private const val PORT = 5554

    /** True when the console answers, so console-driven tests can skip on a real phone. */
    val available: Boolean by lazy { runCatching { send("help") }.isSuccess }

    fun incomingCall(number: String) = send("gsm call $number")
    fun hangUp(number: String) = send("gsm cancel $number")
    fun accept(number: String) = send("gsm accept $number")
    fun list(): String = send("gsm list")

    /**
     * Numbers of every call the emulated modem knows about, as `gsm list` prints them
     * ("inbound from 5559998888 : active", "outbound to  +15550101111 : dialing").
     */
    fun activeNumbers(): List<String> =
        Regex("(?:inbound from|outbound to)\\s+(\\S+)").findAll(list()).map { it.groupValues[1] }.toList()

    /** True when `gsm list` shows a call whose digits end with [number]'s digits. */
    fun hasCallWith(number: String): Boolean {
        val want = number.filter(Char::isDigit).takeLast(10)
        return activeNumbers().any { it.filter(Char::isDigit).endsWith(want) }
    }

    /** Ends every call the modem knows about. */
    fun hangUpAll() = activeNumbers().forEach { runCatching { hangUp(it) } }

    @Synchronized
    fun send(command: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), 3_000)
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            readUntilOk(reader) // banner
            val token = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("consoleToken")
            if (!token.isNullOrBlank()) {
                writer.print("auth $token\r\n"); writer.flush()
                readUntilOk(reader)
            }
            writer.print("$command\r\n"); writer.flush()
            val out = readUntilOk(reader)
            writer.print("quit\r\n"); writer.flush()
            return out
        }
    }

    private fun readUntilOk(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("KO")) throw IllegalStateException("Emulator console: $line\n$sb")
            if (line == "OK") break
            sb.appendLine(line)
        }
        return sb.toString()
    }
}
