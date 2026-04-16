#!/bin/sh
# Gradle wrapper script

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(dirname "$0")
APP_HOME=$(cd "$APP_HOME" && pwd)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
DEFAULT_JVM_OPTS="-Xmx512m -Xms64m"

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" \
  -Dorg.gradle.appname="$APP_BASE_NAME" \
  org.gradle.wrapper.GradleWrapperMain "$@"
