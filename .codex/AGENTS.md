# Project Notes

## Build Environment Policy

- Treat Windows-hosted Android Studio usage as the canonical project setup.
- Keep shared project config files Windows-friendly by default.
- Do not hardcode WSL-only paths or `/tmp` build directories in shared Gradle config unless explicitly requested.

## SDK Path

- The shared Android SDK for this project is located at `/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux`.
- Do not rely on `local.properties` for the SDK path.
- Use `ANDROID_HOME=/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux` from the WSL environment.

## WSL Build Rule

- When building from WSL, always use command-line overrides instead of editing shared config for WSL-specific behavior.
- WSL builds should provide:
  - `ANDROID_HOME`
  - persistent `GRADLE_USER_HOME`
  - `--project-cache-dir`
  - `-PcodexRootBuildDir=...`
  - `-PcodexAppBuildDir=...`
  - `-Dkotlin.compiler.execution.strategy=in-process`
  - `-Pkotlin.incremental=false`

## Recommended WSL Cache Paths

- Use `GRADLE_USER_HOME=/home/shou/.gradle-codex` so the wrapper and dependency caches survive across runs.
- Keep project build outputs and project cache under `/tmp` to avoid instability on `/mnt/d`.

## Known Good WSL Command

```bash
cd /mnt/d/project/android
ANDROID_HOME=/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux \
GRADLE_USER_HOME=/home/shou/.gradle-codex ./gradlew clean assembleDebug \
  --no-daemon \
  --console=plain \
  --project-cache-dir /tmp/wifi_volume/project-cache \
  -PcodexRootBuildDir=/tmp/wifi_volume/root-build \
  -PcodexAppBuildDir=/tmp/wifi_volume/app-build \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```
