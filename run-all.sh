#!/usr/bin/env bash
# 只发本地 Maven，验证两种依赖模式和缺失 API 保护。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
export KN_SAMPLE_ONLINE=0
REMOTE=false "$ROOT/publish-ohos-capi.sh"
for mode in bom leaf; do
    args=(-PcapiDependencyMode="$mode")
    if [[ -n "${CONSUMER_NATIVE_HOME:-}" ]]; then
        args+=("-Pkotlin.native.home=$CONSUMER_NATIVE_HOME")
    fi
    (cd "$ROOT/consumer-bare" && ./gradlew :kotlinApp:linkDebugSharedOhosArm64 \
        :kotlinApp:linkReleaseSharedOhosArm64 "${args[@]}" --max-workers=4)
done
"$ROOT/verify-protection.sh"
if [[ -n "${HDC_DEVICE:-}" ]]; then
    echo "BOM/leaf 编译与 Debug/Release 保护运行测试通过"
else
    echo "编译通过；未指定 HDC_DEVICE，未执行设备测试"
fi
