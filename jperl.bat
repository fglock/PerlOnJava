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
rem --enable-native-access=ALL-UNNAMED: Required by FFM (Foreign Function & Memory) API
rem   for native system calls (file operations, process management).
set JVM_OPTS=--enable-native-access=ALL-UNNAMED

rem Note on JVM heap settings: do NOT set -XX:SoftMaxHeapSize below -Xmx.
rem That combination triggers an aggressive G1 GC cadence that interacts
rem pathologically with PerlOnJava's weak-ref / selective-refcount
rem machinery (DBIx-Class t/96_is_deteministic_value.t hangs at 100% CPU
rem under SoftMaxHeapSize=2g + Xmx=4g). The JVM auto-default
rem (MaxHeapSize = 1/4 of system RAM) is honored when nothing is set.
rem Override via `JPERL_OPTS=-Xmx<size>` in the environment if needed.

rem Java 23+ warns about sun.misc.Unsafe usage (JEP 471). Add flag to suppress
rem warnings from transitive libraries (ASM, ICU4J, etc.) that still use it.
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    for /f "tokens=1 delims=." %%m in ("%%~v") do (
        if %%m GEQ 23 set JVM_OPTS=%JVM_OPTS% --sun-misc-unsafe-memory-access=allow
    )
)

rem Launch Java
java %JVM_OPTS% %JPERL_OPTS% -cp "%CLASSPATH%;%SCRIPT_DIR%target\perlonjava-5.42.0.jar" org.perlonjava.app.cli.Main %*
