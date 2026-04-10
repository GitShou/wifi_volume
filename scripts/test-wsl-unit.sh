#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$#" -eq 0 ]; then
  TASKS=(testDebugUnitTest)
else
  TASKS=("$@")
fi

export ANDROID_HOME="${ANDROID_HOME:-/mnt/d/project/DevelopmentKit/AndroidStudioCLforLinux}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle-codex}"

PROJECT_CACHE_DIR="${PROJECT_CACHE_DIR:-/tmp/wifi_volume/project-cache}"
ROOT_BUILD_DIR="${ROOT_BUILD_DIR:-/tmp/wifi_volume/root-build}"
APP_BUILD_DIR="${APP_BUILD_DIR:-/tmp/wifi_volume/app-build}"

cd "$ROOT_DIR"

./gradlew "${TASKS[@]}" \
  --no-daemon \
  --console=plain \
  --project-cache-dir "$PROJECT_CACHE_DIR" \
  -PcodexRootBuildDir="$ROOT_BUILD_DIR" \
  -PcodexAppBuildDir="$APP_BUILD_DIR" \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
