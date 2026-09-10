# A20 USB Boot

**A20 USB Boot** is an Android application (and a set of utility bash scripts) that turns your rooted Samsung Galaxy A20 (or similar devices) into a bootable USB flash drive. By emulating a USB Mass Storage device via the Android kernel's USB gadget subsystem, you can directly boot your PC from an ISO file stored on your phone.

## Features
- **Emulate Bootable USB**: Mount any ISO file (Linux, Windows PE, etc.) as a USB CD-ROM / Flash drive directly from your phone.
- **Easy UI**: Simple Jetpack Compose UI to select ISOs and manage the USB state.
- **Auto Cleanup**: "RESTORE STORAGE AND NORMAL USB" button cleanly reverts your phone to regular MTP+ADB mode and deletes the cached ISO file to free up device storage.
- **Data Integrity**: Automatically verifies the ISO's file size and ISO9660 magic signature (CD001) before mounting.
- **CLI Alternative**: Includes bash scripts (`a20-iso-usb.sh`, `usb_setup.sh`) for advanced users who prefer using `adb` or a terminal emulator.

## Prerequisites
Before using this app, ensure your device meets the following requirements:
1. **Root Access**: Magisk, KernelSU, or SuperSU is required to configure the USB gadget and copy the ISO to `/data/local/tmp`.
2. **Custom Kernel**: The kernel MUST support the USB Mass Storage gadget function (`mass_storage,adb`). Default stock kernels usually lack this feature. Check if `/config/usb_gadget/g1/functions/mass_storage.0/` exists.
3. **Storage Space**: You need enough free space in your internal storage (`/data/`) to hold a copy of the selected ISO.

## How to Use the App

1. **Launch the App**: Open **A20 USB Boot** and grant it root permissions when prompted.
2. **Select ISO**: Tap **"Select ISO File"** and choose your desired OS image (e.g., Ubuntu, Windows Recovery).
3. **Prepare USB**: Tap **"PREPARE USB"**. The app will copy the ISO to a root-accessible temporary folder, verify its integrity, and reconfigure the USB gadget.
4. **Boot your PC**: 
   - Connect the phone to your PC via USB.
   - Reboot the PC and enter the Boot Menu (F12, F9, F8, etc., depending on the manufacturer).
   - Select **USB Hard Drive (UEFI)** -> **SAMSUNG File-Stor Gadget0001**.
5. **Restore & Cleanup**: Once you are done installing or booting from the ISO, open the app and tap **"RESTORE STORAGE AND NORMAL USB"**. This will turn your phone back into a normal MTP device and delete the ISO from the temporary folder to save space.

## Using the CLI Scripts (Advanced)

If you prefer using ADB from your host machine, you can use the provided bash scripts.

```bash
# Push the ISO and mount it via ADB
./a20-iso-usb.sh /path/to/ubuntu-live-server.iso

# Restore normal MTP+ADB functionality
./a20-iso-usb.sh --restore
```

## How it Works Under the Hood
1. Copies the selected ISO file to `/data/local/tmp/a20-usb-boot/` using `su`.
2. Temporarily sets SELinux to `permissive`.
3. Disables the current USB configuration (`setprop sys.usb.config none`).
4. Writes the absolute path of the ISO to the mass storage logical unit (`/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file`).
5. Configures the LUN as a standard block device (`cdrom=0`).
6. Re-enables the USB configuration as a mass storage device (`setprop sys.usb.config mass_storage,adb`).

## Disclaimer
This project modifies low-level kernel properties and involves root access. Use it at your own risk. The author is not responsible for any damage or data loss caused by using this application.
