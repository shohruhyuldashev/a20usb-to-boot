package com.example.a20usbboot

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IsoManager(
    private val context: Context,
    private val rootSession: RootSession,
    private val logCallback: suspend (String) -> Unit
) {

    val targetDir = "/data/local/tmp/a20-usb-boot"
    var targetPath = "" // set dynamically based on selected filename
        private set

    /**
     * Get display name from content:// URI
     */
    private fun getFileName(uri: Uri): String {
        var name = "image.iso"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx) ?: name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("IsoManager", "Could not get filename from URI", e)
        }
        // Sanitize: replace spaces and special chars
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    suspend fun prepareIso(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri)
            targetPath = "$targetDir/$fileName"
            logCallback("ISO: $fileName")

            logCallback("Opening file...")
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                logCallback("✗ Could not open file")
                return@withContext false
            }

            logCallback("Preparing target directory...")
            rootSession.execute("mkdir -p $targetDir")
            rootSession.execute("chmod 777 $targetDir")

            // CRITICAL: Clear LUN binding BEFORE deleting old file
            // This prevents kernel from holding a (deleted) file descriptor
            rootSession.writeFile("/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file", "")
            rootSession.execute("rm -f $targetPath")

            logCallback("Copying to $targetPath...")
            val suProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $targetPath"))
            val outputStream = suProcess.outputStream

            val buffer = ByteArray(4 * 1024 * 1024)
            var bytesRead: Int
            var totalCopied = 0L
            var lastReported = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalCopied += bytesRead

                if (totalCopied - lastReported > 100 * 1024 * 1024) {
                    logCallback("  ${totalCopied / (1024 * 1024)} MB...")
                    lastReported = totalCopied
                }
            }
            inputStream.close()
            outputStream.flush()
            outputStream.close()

            val exitCode = suProcess.waitFor()
            if (exitCode != 0) {
                logCallback("✗ Copy failed (exit $exitCode)")
                return@withContext false
            }

            logCallback("✓ Copied ${totalCopied / (1024 * 1024)} MB")

            // Verify size on disk
            logCallback("Verifying...")
            val (sizeOut, sizeOk) = rootSession.execute("stat -c '%s' $targetPath")
            if (sizeOk) {
                val diskSize = sizeOut.replace("'", "").trim().toLongOrNull()
                if (diskSize != null && diskSize == totalCopied) {
                    logCallback("✓ Size OK: $diskSize bytes")
                } else {
                    logCallback("✗ Size mismatch: copied=$totalCopied, disk=$diskSize")
                    return@withContext false
                }
            }

            // Validate ISO9660 magic (CD001 at offset 32769)
            logCallback("Checking ISO format...")
            val (magic, magicOk) = rootSession.execute("dd if=$targetPath bs=1 skip=32769 count=5 2>/dev/null")
            if (!magicOk || !magic.contains("CD001")) {
                logCallback("✗ Not a valid ISO (CD001 not found)")
                return@withContext false
            }
            logCallback("✓ Valid ISO9660")

            rootSession.execute("chmod 666 $targetPath")

            logCallback("✓ Ready: $fileName")
            return@withContext true
        } catch (e: Exception) {
            logCallback("✗ Error: ${e.message}")
            Log.e("IsoManager", "prepareIso failed", e)
            return@withContext false
        }
    }

    suspend fun cleanupStorage() = withContext(Dispatchers.IO) {
        logCallback("Cleaning up ISO storage...")
        rootSession.execute("rm -rf $targetDir/*")
        logCallback("✓ Storage cleaned")
    }
}
