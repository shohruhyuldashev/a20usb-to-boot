package com.example.a20usbboot

class DeviceChecker(private val rootSession: RootSession, private val logCallback: suspend (String) -> Unit) {

    var udcName: String = ""
        private set

    suspend fun checkCompatibility(): Boolean {
        logCallback("\n--- A20 USB Boot Compatibility ---")
        
        // Set SELinux to permissive to avoid file permission issues globally
        rootSession.execute("setenforce 0")
        
        var allOk = true

        suspend fun check(name: String, command: String): Boolean {
            val (output, success) = rootSession.execute(command)
            if (!success) {
                logCallback("✗ $name")
                allOk = false
            } else {
                logCallback("✓ $name")
            }
            return success
        }

        check("ConfigFS", "ls /config/usb_gadget/g1")
        check("Mass Storage Function", "ls /config/usb_gadget/g1/functions/mass_storage.0")
        check("LUN0", "ls /config/usb_gadget/g1/functions/mass_storage.0/lun.0")
        
        // Optional kernel config check
        val (gzOut, gzOk) = rootSession.execute("ls /proc/config.gz")
        if (gzOk) {
            val (confOut, _) = rootSession.execute("zcat /proc/config.gz | grep -E 'CONFIG_USB_CONFIGFS_MASS_STORAGE|CONFIG_USB_F_MASS_STORAGE'")
            if (confOut.contains("CONFIG_USB_CONFIGFS_MASS_STORAGE=y") || confOut.contains("CONFIG_USB_F_MASS_STORAGE=y")) {
                logCallback("✓ Kernel Mass Storage configs found")
            } else {
                logCallback("! Kernel configs missing in /proc/config.gz (might still work)")
            }
        }
        
        val udcResult = rootSession.execute("ls /sys/class/udc/ | head -n 1")
        if (udcResult.second && udcResult.first.isNotBlank()) {
            udcName = udcResult.first
            logCallback("✓ UDC: $udcName")
        } else {
            logCallback("✗ UDC not found")
            allOk = false
        }

        return allOk
    }
}
