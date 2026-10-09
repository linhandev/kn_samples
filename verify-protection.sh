#!/usr/bin/env bash
# 生产者生成测试 KLIB，消费者 runtime 编译；提供设备 ID 时执行真机验证。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
: "${KOTLIN_NATIVE_HOME:?需要生产者 CPF Native 目录}"
: "${CONSUMER_NATIVE_HOME:?需要待验证的消费者 Native 目录}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
export KN_SAMPLE_ONLINE=0
OUT="$ROOT/producer/ohos-capi/build/protection"
VERSION=$(sed -n 's/^klibVersion=//p' "$ROOT/producer/gradle.properties")
(cd "$ROOT/producer" && ./gradlew :ohos-capi:prepareProtectionProbe "-Pkotlin.native.home=$KOTLIN_NATIVE_HOME" --max-workers=4)
"$KOTLIN_NATIVE_HOME/bin/cinterop" -target ohos_arm64 -def "$OUT/probe.def" \
    -compiler-option "-I$ROOT/tests/protection" -no-default-libs -o "$OUT/probe"
for mode in debug release; do
    opts=(-g)
    [[ "$mode" == release ]] && opts=(-opt)
    "$CONSUMER_NATIVE_HOME/bin/konanc" "$ROOT/tests/protection/main.kt" \
        -target ohos_arm64 -produce program -no-default-libs "${opts[@]}" -o "$OUT/check-$mode" \
        -library "$OUT/probe.klib" \
        -library "$ROOT/m2/org/cpf/kotlin/HiLog/$VERSION/HiLog-$VERSION.klib" \
        -library "$ROOT/m2/org/cpf/kotlin/posix/$VERSION/posix-$VERSION.klib" \
        -Xpurge-user-libs -Xklib-duplicated-unique-name-strategy=allow-all-with-warning
    if [[ -n "${HDC_DEVICE:-}" ]]; then
        # 调用者须先取得此设备的 machine-lease。
        device_file="/data/local/tmp/capi-protection-$mode-$$.kexe"
        hdc -t "$HDC_DEVICE" file send "$OUT/check-$mode.kexe" "$device_file"
        hdc -t "$HDC_DEVICE" shell "chmod 700 '$device_file'; '$device_file'; status=\$?; rm -f '$device_file'; echo CAPI_EXIT=\$status" | tee "$OUT/device-$mode.log"
        grep -q 'CAPI_PROTECTION_PASS' "$OUT/device-$mode.log"
        grep -q 'CAPI_EXIT=0' "$OUT/device-$mode.log"
        [[ "$(grep -c 'CAPI_MISSING_CAUGHT' "$OUT/device-$mode.log")" == 3 ]]
    fi
done
