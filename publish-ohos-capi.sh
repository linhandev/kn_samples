#!/usr/bin/env bash
# 默认只发布到仓库内 m2；REMOTE=true 才显式启用远端 CAPI 发布。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
args=(--parallel --max-workers=4)
if [[ -n "${KOTLIN_NATIVE_HOME:-}" ]]; then
    args+=("-Pkotlin.native.home=$KOTLIN_NATIVE_HOME")
fi
if [[ "${REMOTE:-false}" == true ]]; then
    export KN_SAMPLE_ONLINE=1
    capi_tasks=(:ohos-capi:publish :hms-capi:publish)
else
    export KN_SAMPLE_ONLINE=0
    capi_tasks=(:ohos-capi:publishAllPublicationsToM2Repository :hms-capi:publishAllPublicationsToM2Repository)
fi
cd "$ROOT/producer"
./gradlew "${capi_tasks[@]}" "${args[@]}"
# 测试用包始终只有本地 m2 仓库；先发布 CAPI，再解析业务包依赖。
./gradlew :biz-klib:publishAllPublicationsToM2Repository :static-lib-demo:publishAllPublicationsToM2Repository "${args[@]}"
python3 "$ROOT/verify-publication.py" --repo "$ROOT"
