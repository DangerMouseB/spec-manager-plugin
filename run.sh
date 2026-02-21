#!/bin/bash
# run.sh — Build Spec Manager plugin and launch a JetBrains IDE sandbox.
#
# Usage:
#   ./run.sh              # launches CLion (default)
#   ./run.sh clion        # launches CLion
#   ./run.sh pycharm      # launches PyCharm
#
# The built-in Gradle runIde task crashes on JetBrains 2025.3 IDEs
# (gradle-intellij-plugin 1.x bug). This script replaces it.

set -euo pipefail
cd "$(dirname "$0")"

IDE="${1:-clion}"
case "$IDE" in
    clion)   APP="/Applications/CLion.app"   ;;
    pycharm) APP="/Applications/PyCharm.app" ;;
    *)       echo "Usage: $0 [clion|pycharm]"; exit 1 ;;
esac

if [ ! -d "$APP" ]; then
    echo "ERROR: $APP not found"
    exit 1
fi

# Build plugin
echo "==> Building plugin..."
./gradlew buildPlugin -x runIde -x buildSearchableOptions || exit 1

# Set up sandbox
SANDBOX="$(pwd)/build/idea-sandbox"
CONFIG_DIR="$SANDBOX/config"
SYSTEM_DIR="$SANDBOX/system"
LOG_DIR="$SANDBOX/log"
PLUGINS_DIR="$SANDBOX/plugins"

mkdir -p "$CONFIG_DIR" "$SYSTEM_DIR" "$LOG_DIR" "$PLUGINS_DIR"

# Unzip plugin into sandbox (clean old version first)
PLUGIN_ZIP=$(ls -1t build/distributions/*.zip 2>/dev/null | head -1)
if [ -z "$PLUGIN_ZIP" ]; then
    echo "ERROR: No plugin zip found in build/distributions/"
    exit 1
fi
rm -rf "$PLUGINS_DIR/spec-manager"
unzip -qo "$PLUGIN_ZIP" -d "$PLUGINS_DIR"
echo "==> Installed: $PLUGIN_ZIP"

# Launch IDE with sandbox paths
echo "==> Launching $APP..."
open "$APP" --args \
    "-Didea.config.path=$CONFIG_DIR" \
    "-Didea.system.path=$SYSTEM_DIR" \
    "-Didea.log.path=$LOG_DIR" \
    "-Didea.plugins.path=$PLUGINS_DIR" \
    "-Xmx2g"

echo "==> IDE launched. Plugin logs: $LOG_DIR/idea.log"
