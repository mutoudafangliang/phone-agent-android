#!/bin/sh

# Gradle startup script for Unix

##############################################################################
#
#   Gradle wrapper script for running Gradle builds.
#
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"

# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done

APP_HOME=`dirname "$PRG"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

# Use the maximum available from the JVM to avoid the "ISBN error"
# caused by the Gradle Kotlin DSL
# DISABLED: we let Gradle manage memory

# Collect all arguments for the java command, following the shell quoting rules
# - "" will be stripped
# - true/false arguments will be kept as-is
# - arguments with spaces will be split (shell does this)
GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.daemon=true"

# Escape application args
# shellcheck disable=SC2034
save () {
    for i do printf '%s\n' "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/"; done
    echo " "
}

APP_ARGS=$(save "$@")

# Load environment variable defaults
if [ -f "$APP_HOME/gradlew.d/bin/setEnv.sh" ]; then
    . "$APP_HOME/gradlew.d/bin/setEnv.sh"
fi

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executable
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation, or install a JDK."
fi

# Increase the maximum file descriptors if we can
if [ "$(uname)" = "Linux" ] || [ "$(uname)" = "Darwin" ]; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ] ; then
        # Change the max file descriptor limit from soft to hard
        ulimit -n $MAX_FD_LIMIT
    fi
fi

# Build the command line

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
