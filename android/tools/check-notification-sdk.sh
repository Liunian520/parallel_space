#!/bin/sh
# 用公开 android.jar 编译通知隔离相关 Java，并核对 Notification 上没有 setChannelId/setGroup。
# 不打 APK。打包机可设 ANDROID_JAR，或提供 ANDROID_HOME/platforms/android-*/android.jar。
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
JAR=${ANDROID_JAR:-}
if [ -z "$JAR" ] && [ -n "${ANDROID_HOME:-}" ]; then
  JAR=$(ls -1 "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "需要 ANDROID_JAR，或 ANDROID_HOME/platforms/android-*/android.jar" >&2
  exit 2
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -p "$ROOT/android/app/libs/bcore-release.aar" classes.jar > "$TMP/bcore-classes.jar"
cat > "$TMP/CloneNames.java" << 'EOF'
package com.parallelspace.parallel_space;
public final class CloneNames {
    public static final String FILE = "clone_names.json";
    private CloneNames() {}
}
EOF
SRC="$ROOT/android/app/src/main/java/com/parallelspace/parallel_space"
javac --release 17 -encoding UTF-8 -cp "$JAR:$TMP/bcore-classes.jar" -d "$TMP/out" \
  "$TMP/CloneNames.java" \
  "$SRC/CloneIdentityPolicy.java" \
  "$SRC/CloneIdentityFile.java" \
  "$SRC/CloneNotificationPolicy.java" \
  "$SRC/CloneNotificationIndex.java" \
  "$SRC/TaskLabelCompat.java" \
  "$SRC/CloneIdentityCompat.java" \
  "$SRC/CloneNotificationCompat.java"
javac --release 17 -encoding UTF-8 -cp "$JAR" -d "$TMP/api" "$ROOT/android/tools/PublicNotificationApiTest.java"
java -cp "$JAR:$TMP/api" PublicNotificationApiTest
echo "check-notification-sdk ok ($JAR)"
