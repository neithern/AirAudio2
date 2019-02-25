#!/usr/bin/env bash

adb push app/build/outputs/apk/release/app-release.apk /sdcard/
adb shell /system/bin/pm install -r -t /sdcard/app-release.apk
adb shell am startservice -n com.github.neithern.airaudio/.AirAudioService -e name 小讯 -e output system
