#!/bin/sh
# Gradle wrapper placeholder - use "gradle" command directly if wrapper not present
# CI uses gradle/actions/setup-gradle which provides the 'gradle' command
if command -v gradle &> /dev/null; then
  exec gradle "$@"
else
  echo "Gradle not found. Install Gradle or use gradlew from Android Studio."
  exit 1
fi
