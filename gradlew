#!/usr/bin/env sh
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
die() { echo; echo "ERROR: $* "; echo; exit 1; }
warn() { echo "$*"; }

# OS specific support
cygwin=false; msys=false; darwin=false; nonstop=false
case "`uname`" in CYGWIN*) cygwin=true;; Darwin*) darwin=true;; MSYS*|MINGW*) msys=true;; NONSTOP*) nonstop=true;; esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ]; then die "JAVA_HOME is set but java binary not found: $JAVACMD"; fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "JAVA_HOME not set and java not found in PATH."
fi

# Collect all arguments for the java command
set -- org.gradle.wrapper.GradleWrapperMain "$@"
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS -classpath "$CLASSPATH" "$@"
