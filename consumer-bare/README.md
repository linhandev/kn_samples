> 历史说明：本文记录旧聚合发布/链接方案。当前 22-0.5 的独立 leaf、内联异常 shim 和验证步骤见[根目录说明](../README.md)，下文旧版本号和命令不作为当前操作入口。

# Kotlin/Native Exception Demo (Minimal)

Keep `bare` branch a starting point for doing a demo, impl demos on another branch.

Full build command.

```shell
clear
hdc uninstall com.kotlin.demo \
./gradlew clean \
./gradlew --stop \
./gradlew startHarmonyAppDebug --rerun-tasks
```

## Bundle name (from project)

The installed app’s **bundle name** is **`app.bundleName`** in **`harmonyApp/AppScope/app.json5`** (for this sample it is `com.kotlin.demo`). Use the same value for `hdc uninstall`, `aa start`, and filtering crash logs.

Read it from the repo (from the project root):

```shell
grep bundleName harmonyApp/AppScope/app.json5
```

## Pull the latest crash / fault log for this app

Fault dumps for apps usually land under **`/data/log/faultlog/faultlogger/`** (freeze-related dumps often under **`/data/log/faultlog/freeze_ext/`**). Filenames typically include the **bundle name**, so you can take the newest matching file.

From the project root (macOS/Linux; strips a trailing CR from `hdc` output):

```shell
bundle=$(grep bundleName harmonyApp/AppScope/app.json5 | sed -n 's/.*"bundleName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
latest=$(hdc shell "ls -t /data/log/faultlog/faultlogger/" | tr -d '\r' | grep -F "$bundle" | head -1)
hdc file recv "/data/log/faultlog/faultlogger/$latest" ./
```

Freeze logs for the same app (same idea, different directory):

```shell
latest=$(hdc shell "ls -t /data/log/faultlog/freeze_ext/" | tr -d '\r' | grep -F "$bundle" | head -1)
hdc file recv "/data/log/faultlog/freeze_ext/$latest" ./
```

If `latest` is empty, list recent files and pick the one whose name matches your bundle: `hdc shell "ls -lt /data/log/faultlog/faultlogger/ | head -n 20"`.
