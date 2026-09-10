#!/usr/bin/env bash
set -euo pipefail

# Samsung A20 ISO -> USB Mass Storage avtomatizatsiyasi
# Talablar:
#   - host: adb, sha256sum
#   - A20: unlocked/rooted, custom kernel with USB Mass Storage
#   - vendor Samsung USB init supports mass_storage,adb
#
# Ishlatish:
#   ./a20-iso-usb.sh /path/to/image.iso
#   ./a20-iso-usb.sh /path/to/image.iso SERIAL
#
# Qo'shimcha:
#   ./a20-iso-usb.sh --restore
#
# Eslatma: script HP/PC diskiga hech narsa yozmaydi.
# U faqat A20'ni USB Mass Storage gadget sifatida tayyorlaydi.

REMOTE_DIR="/data/local/tmp"
REMOTE_ISO="$REMOTE_DIR/boot-a20.iso"
GADGET="/config/usb_gadget/g1"
LUN="$GADGET/functions/mass_storage.0/lun.0"

log()  { printf '\n[+] %s\n' "$*"; }
warn() { printf '\n[!] %s\n' "$*" >&2; }
die()  { printf '\n[-] %s\n' "$*" >&2; exit 1; }

ADB=(adb)
if [[ $# -ge 2 && "$2" != "--restore" ]]; then
    ADB=(adb -s "$2")
fi

adb_cmd() {
    "${ADB[@]}" shell su -c "$1"
}

restore_mtp() {
    log "A20 USB profilini odatiy MTP + ADB holatiga qaytaryapman..."
    adb_cmd "setprop sys.usb.config mtp,adb" || true
    sleep 3
    printf '\n'
    "${ADB[@]}" shell getprop sys.usb.config || true
    log "Tayyor. Telefon endi oddiy MTP + ADB profilida bo'lishi kerak."
}

if [[ "${1:-}" == "--restore" ]]; then
    restore_mtp
    exit 0
fi

[[ $# -ge 1 ]] || die "ISO yo'lini bering: $0 /path/to/image.iso [ADB_SERIAL]"
ISO="$1"

[[ -f "$ISO" ]] || die "Fayl topilmadi: $ISO"
command -v adb >/dev/null || die "adb topilmadi. Avval adb o'rnating."

log "ISO fayli"
ls -lh "$ISO"
ISO_SIZE=$(stat -c '%s' "$ISO")
ISO_HASH=$(sha256sum "$ISO" | awk '{print $1}')
printf '  Size : %s bytes\n  SHA256: %s\n' "$ISO_SIZE" "$ISO_HASH"

log "A20 ADB ulanishini tekshiryapman..."
"${ADB[@]}" wait-for-device
"${ADB[@]}" get-state | grep -qx device || die "ADB device holatida emas."

printf '\nADB device:\n'
"${ADB[@]}" devices -l

log "Root va kernelni tekshiryapman..."
adb_cmd "id"
adb_cmd "uname -a"

# MUHIM QO'SHIMCHA: SELinux'ni permissive qilish. Busiz fayl yozishda "Permission denied" berishi mumkin.
log "SELinux rejimini vaqtincha permissive'ga o'tkazyapman..."
adb_cmd "setenforce 0"

log "A20 bo'sh joyini tekshiryapman..."
adb_cmd "df -h /data/local/tmp"

AVAIL_KB=$(adb_cmd "df -k /data/local/tmp | tail -1 | awk '{print \$4}'" | tr -d '\r')
[[ "$AVAIL_KB" =~ ^[0-9]+$ ]] || die "Bo'sh joyni aniqlab bo'lmadi."
AVAIL_BYTES=$(( AVAIL_KB * 1024 ))
(( AVAIL_BYTES > ISO_SIZE + 256*1024*1024 )) || die "A20'da ISO uchun yetarli joy yo'q. Kamida ISO hajmi + 256 MiB zaxira kerak."

log "Eski ISO faylini o'chirish (agar mavjud bo'lsa)..."
adb_cmd "rm -f '$REMOTE_ISO'"

log "ISO A20'ga ADB orqali ko'chirilmoqda..."
"${ADB[@]}" push "$ISO" "$REMOTE_ISO"

log "A20 dagi fayl hajmini tekshiryapman..."
REMOTE_SIZE=$(adb_cmd "stat -c '%s' '$REMOTE_ISO'" | tr -d '\r')
[[ "$REMOTE_SIZE" == "$ISO_SIZE" ]] || die "Hajm mos emas: host=$ISO_SIZE, phone=$REMOTE_SIZE"

log "A20 dagi SHA-256 tekshirilmoqda..."
REMOTE_HASH=$(adb_cmd "sha256sum '$REMOTE_ISO' | awk '{print \$1}'" | tr -d '\r')
[[ "$REMOTE_HASH" == "$ISO_HASH" ]] || die "SHA-256 mos emas! ISO'ni gadgetga ulamayman."

printf '  Host :  %s\n  Phone:  %s\n' "$ISO_HASH" "$REMOTE_HASH"

log "USB gadgetni vaqtincha o'chiryapman..."
adb_cmd "setprop sys.usb.config none" || true
sleep 2

log "Samsung Mass Storage + ADB profilini yoqyapman..."
adb_cmd "setprop sys.usb.config mass_storage,adb"
sleep 3

log "Mass Storage LUN mavjudligini tekshiryapman..."
adb_cmd "test -d '$LUN' || exit 1" || die "mass_storage.0/lun.0 topilmadi. Custom kernel/vendor init holatini tekshiring."

log "LUN'ni disk rejimiga o'tkazishga urinilmoqda..."
if ! adb_cmd "echo 0 > '$LUN/cdrom'"; then
    warn "cdrom=0 yozish muvaffaqiyatsiz bo'ldi."
    warn "Bu kernel/vendor implementatsiyasida qo'llab-quvvatlanmasligi mumkin."
fi

log "ISO'ni LUN backing file sifatida ulayapman..."
adb_cmd "printf '%s' '$REMOTE_ISO' > '$LUN/file'"

log "LUN holatini tekshiryapman..."
printf '\n--- LUN file ---\n'
adb_cmd "cat '$LUN/file'"
printf '\n--- LUN cdrom ---\n'
adb_cmd "cat '$LUN/cdrom' 2>/dev/null || true"
printf '\n--- LUN ro ---\n'
adb_cmd "cat '$LUN/ro' 2>/dev/null || true"

log "USB profilini tekshiryapman..."
printf 'sys.usb.config = '
"${ADB[@]}" shell getprop sys.usb.config | tr -d '\r'
printf 'sys.usb.state  = '
"${ADB[@]}" shell getprop sys.usb.state | tr -d '\r'

log "USB host enumeration uchun tayyor."
cat <<EOF

============================================================
A20 USB BOOT MEDIUM TAYYOR
============================================================
ISO       : $ISO
A20 file  : $REMOTE_ISO
Size      : $ISO_SIZE bytes
SHA-256   : $ISO_HASH

HP/PC UEFI:
  Boot Menu -> USB Hard Drive (UEFI)
  -> SAMSUNG File-Stor Gadget0001

Windows recovery kerak bo'lsa:
  Repair your computer
  -> Troubleshoot
  -> Advanced options
  -> Command Prompt

Ish tugagach MTP + ADB ga qaytarish:
  $0 --restore
============================================================
EOF
