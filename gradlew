#!/bin/sh
# Gradle wrapper script

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(dirname "$0")
APP_HOME=$(cd "$APP_HOME" && pwd)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
DEFAULT_JVM_OPTS="-Xmx512m -Xms64m"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

exec "$JAVA_CMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" \
  -Dorg.gradle.appname="$APP_BASE_NAME" \
  org.gradle.wrapper.GradleWrapperMain "$@"
