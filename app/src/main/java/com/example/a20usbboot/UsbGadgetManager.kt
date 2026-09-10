package com.example.a20usbboot

import android.util.Log

class UsbGadgetManager(
    private val rootSession: RootSession,
    private val deviceChecker: DeviceChecker,
    private val logCallback: suspend (String) -> Unit
) {

    suspend fun setupBootableUsb(isoPath: String): Boolean {
        logCallback("\n--- Configuring USB Mass Storage ---")

        val udc = deviceChecker.udcName
        if (udc.isBlank()) {
            logCallback("✗ UDC name is unknown.")
            return false
        }

        // Verify ISO file exists
        val (fileCheck, fileExists) = rootSession.execute("test -f '$isoPath' && stat -c '%s' '$isoPath'")
        if (!fileExists) {
            logCallback("✗ ISO not found: $isoPath")
            return false
        }
        logCallback("✓ ISO verified: $fileCheck bytes")

        logCallback("Setting SELinux permissive...")
        rootSession.execute("setenforce 0")

        // Step 1: Disable USB gadget
        logCallback("Disabling USB gadget...")
        rootSession.execute("setprop sys.usb.config none")
        kotlinx.coroutines.delay(2000)

        // Step 2: CRITICAL - Clear old LUN binding before anything else
        // This releases the kernel's file descriptor from previous (deleted) bindings
        logCallback("Clearing old LUN binding...")
        rootSession.writeFile("/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file", "")
        kotlinx.coroutines.delay(500)

        // Step 3: Write new ISO path WHILE gadget is detached (no UDC bound)
        logCallback("Setting new ISO path...")
        val writeOk = rootSession.writeFile(
            "/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file",
            isoPath
        )
        if (!writeOk) {
            logCallback("✗ Failed to set ISO path (pre-attach)")
            // Diagnostic
            rootSession.execute("cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file")
            rootSession.execute("ls -la '$isoPath'")
            return false
        }

        // Step 4: Set cdrom=0
        rootSession.writeFile("/config/usb_gadget/g1/functions/mass_storage.0/lun.0/cdrom", "0")

        // Step 5: Now activate the gadget with mass_storage,adb
        logCallback("Activating mass_storage,adb...")
        rootSession.execute("setprop sys.usb.config mass_storage,adb")

        // Step 6: Wait for USB state
        logCallback("Waiting for USB ready...")
        var ready = false
        for (i in 1..10) {
            kotlinx.coroutines.delay(1000)
            val (state, _) = rootSession.execute("getprop sys.usb.state")
            if (state.contains("mass_storage")) {
                logCallback("✓ USB state: $state (${i}s)")
                ready = true
                break
            }
            logCallback("  waiting... ($i/10)")
        }
        if (!ready) {
            logCallback("✗ USB did not switch to mass_storage")
            return false
        }

        // Step 7: Verify LUN file is still set correctly
        logCallback("Verifying LUN...")
        val (lunFile, _) = rootSession.execute("cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file")
        if (lunFile.contains(isoPath) && !lunFile.contains("deleted")) {
            logCallback("✓ LUN verified: $lunFile")
        } else {
            logCallback("✗ LUN has wrong value: $lunFile")
            return false
        }

        logCallback("\n✓ USB Mass Storage READY!")
        logCallback("HP/PC Boot Menu → USB Hard Drive (UEFI)")
        logCallback("→ SAMSUNG File-Stor Gadget0001")
        return true
    }

    suspend fun restoreNormal(): Boolean {
        logCallback("\n--- Restoring Normal USB ---")
        rootSession.execute("setenforce 0")

        logCallback("Clearing LUN file...")
        rootSession.writeFile("/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file", "")

        logCallback("Restoring MTP+ADB...")
        rootSession.execute("setprop sys.usb.config mtp,adb")
        kotlinx.coroutines.delay(3000)

        val (usbState, _) = rootSession.execute("getprop sys.usb.state")
        logCallback("✓ USB state: $usbState")
        return true
    }
}
