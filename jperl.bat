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

rem Launch Java
rem JVM options explained:
rem   --enable-native-access=ALL-UNNAMED: Required by JNR-POSIX library for native system calls
rem     (file operations, process management). Can be removed if JNR-POSIX is replaced.
rem   --sun-misc-unsafe-memory-access=allow: Suppresses deprecation warnings from JFFI library
rem     (used by JNR). Can be removed when JFFI updates to use MemorySegment API (Java 22+).
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow %JPERL_OPTS% -cp "%CLASSPATH%;%SCRIPT_DIR%target\perlonjava-5.42.0.jar" org.perlonjava.app.cli.Main %*
