#!/bin/bash
# WygOTA Ubuntu/Linux installer
# Checks for Java 17+ and VLC, offers to install them, then runs the app.

set -e
cd "$(dirname "$0")"

echo "WygOTA — over-the-air TV"
echo "------------------------"

# Check Java
if ! command -v java >/dev/null 2>&1; then
    echo "Java is not installed."
    read -p "Install OpenJDK 17? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        sudo apt update && sudo apt install -y openjdk-17-jre
    else
        echo "Java 17+ is required. Install it and run this script again."
        exit 1
    fi
else
    echo "Java found: $(java -version 2>&1 | head -1)"
fi

# Check VLC
if ! command -v vlc >/dev/null 2>&1 && ! ldconfig -p | grep -q libvlc; then
    echo "VLC (libVLC) is not installed."
    read -p "Install VLC? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        sudo apt update && sudo apt install -y vlc
    else
        echo "VLC is required for video playback. Install it and run this script again."
        exit 1
    fi
else
    echo "VLC found."
fi

echo
echo "Starting WygOTA..."
exec ./bin/wygota "$@"
