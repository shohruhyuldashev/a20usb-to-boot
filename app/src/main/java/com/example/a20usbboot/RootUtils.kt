package com.example.a20usbboot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class RootSession(private val logCallback: suspend (String) -> Unit) {
    private var process: Process? = null
    private var os: DataOutputStream? = null
    private var reader: BufferedReader? = null

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            logCallback("Requesting root...")
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            val uid = execute("id")
            if (uid.first.contains("uid=0")) {
                logCallback("✓ ${uid.first}")
                return@withContext true
            } else {
                logCallback("✗ Root access denied")
                return@withContext false
            }
        } catch (e: Exception) {
            logCallback("✗ Failed to start root session: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Execute a command in the interactive su shell.
     * NOTE: Do NOT use this for commands with > redirects to sysfs/configfs.
     * Use writeFile() for that instead.
     */
    suspend fun execute(command: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        logCallback("> $command")
        os?.writeBytes("$command 2>&1\n")
        os?.writeBytes("echo __EOF__\$?\n")
        os?.flush()

        val output = StringBuilder()
        var line: String?
        var exitCodeStr = "0"

        while (true) {
            line = reader?.readLine()
            if (line == null) break
            if (line.startsWith("__EOF__")) {
                exitCodeStr = line.substring(7)
                break
            }
            if (line.isNotBlank()) {
                logCallback("  $line")
                output.append(line).append("\n")
            }
        }

        val isSuccess = exitCodeStr == "0"
        if (isSuccess) {
            logCallback("✓ Success")
        } else {
            logCallback("✗ Failed with exit code $exitCodeStr")
        }

        Pair(output.toString().trim(), isSuccess)
    }

    /**
     * Write a value to a sysfs/configfs file using tee via su.
     * Avoids shell redirect issues by piping value directly to tee's stdin.
     */
    suspend fun writeFile(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
        try {
            logCallback("> write '$value' → $path")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "tee $path"))
            // Pipe the value directly to tee's stdin
            val out = proc.outputStream
            out.write(value.toByteArray())
            out.flush()
            out.close()
            // Read tee's stdout (it echoes input) to prevent pipe blocking
            proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                logCallback("✓ Success")
            } else {
                logCallback("✗ Failed (exit $exitCode)")
                Log.e("RootSession", "writeFile failed: path=$path value=$value")
            }
            exitCode == 0
        } catch (e: Exception) {
            logCallback("✗ writeFile error: ${e.message}")
            false
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        try {
            os?.writeBytes("exit\n")
            os?.flush()
            process?.waitFor()
        } catch (e: Exception) {}
    }
}
