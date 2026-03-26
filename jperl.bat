@echo off
rem PerlOnJava Launcher Script for Windows
rem This script launches the PerlOnJava runtime environment, which provides
rem a Java-based implementation of the Perl programming language.
rem Repository: github.com/fglock/PerlOnJava

rem Get the directory where this script is located
set SCRIPT_DIR=%~dp0

rem Get the full path to this script to set $^X correctly
set JPERL_PATH=%~f0

rem Set environment variable for PerlOnJava to use as $^X
set PERLONJAVA_EXECUTABLE=%JPERL_PATH%

rem Determine JVM options based on Java version
rem --enable-native-access=ALL-UNNAMED: Required by JNR-POSIX library for native system calls
rem   (file operations, process management). Can be removed if JNR-POSIX is replaced.
rem --sun-misc-unsafe-memory-access=allow: Needed for Java 24+ to suppress JFFI warnings.
rem   The flag was introduced in Java 23 (JEP 471) with default 'allow'.
rem   In Java 24 (JEP 498), the default changed to 'warn', so we need to specify 'allow'.
rem   Java 21-22 don't have this flag.
set JVM_OPTS=--enable-native-access=ALL-UNNAMED

rem Get Java major version and add unsafe flag for Java 24+
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER_RAW=%%v
)
rem Remove quotes and get major version
set JAVA_VER_RAW=%JAVA_VER_RAW:"=%
for /f "delims=." %%a in ("%JAVA_VER_RAW%") do set JAVA_MAJOR=%%a

if %JAVA_MAJOR% GEQ 24 set JVM_OPTS=%JVM_OPTS% --sun-misc-unsafe-memory-access=allow

rem Launch Java
java %JVM_OPTS% %JPERL_OPTS% -cp "%CLASSPATH%;%SCRIPT_DIR%target\perlonjava-5.42.0.jar" org.perlonjava.app.cli.Main %*
