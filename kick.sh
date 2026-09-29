#!/bin/bash
cd /home/z/my-project/DKVideoPlayer
JAVA_HOME=/home/z/my-project/android_build/jdk17 ANDROID_HOME=/home/z/my-project/android_build ./gradlew :dkplayer-sample:assembleRelease --no-daemon > build.log 2>&1 &
echo started
