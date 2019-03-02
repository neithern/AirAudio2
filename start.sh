#!/usr/bin/env bash

adb push app/build/outputs/apk/release/app-release.apk /sdcard/
adb shell /system/bin/pm install -r -t /sdcard/app-release.apk

# Start parameters: -e name ServerName -e output system/music -e channel left/right/stereo
adb shell am startservice -n com.github.neithern.airaudio/.AirAudioService
