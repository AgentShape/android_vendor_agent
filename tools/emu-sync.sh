#!/bin/bash
# 把 out-emu 里的 system_ext 推到正在运行的模拟器上（ADR-0015 的日常迭代流程）。
# 用法：vendor/agent/tools/emu-sync.sh [--no-reboot]
set -euo pipefail

TOP=$(cd "$(dirname "$0")/../../.." && pwd)
export ANDROID_PRODUCT_OUT=$TOP/out-emu/target/product/emu64x

wait_boot() {
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    adb root >/dev/null
    adb wait-for-device
    until [ "$(adb shell id -u 2>/dev/null | tr -d '\r')" = 0 ]; do sleep 1; done
}

wait_boot
adb remount >/dev/null
adb sync system_ext
# adb sync 推上去的新文件在 overlayfs 上是 system_file，需要按 file_contexts 重新打标签。
adb shell restorecon -R /system_ext/bin/agentd /system_ext/priv-app/AgentApp /system_ext/priv-app/AgentFramework /system_ext/lib64/libagentjs.so /system_ext/etc/init/agentd.rc

if [ "${1:-}" != "--no-reboot" ]; then
    adb reboot
    wait_boot
    adb remount >/dev/null
fi
adb shell getprop init.svc.agentd
