@echo off
rem PerlOnJava Launcher Script for Windows
rem This script launches the PerlOnJava runtime environment, which provides
rem a Java-based implementation of the Perl programming language.
rem Repository: github.com/fglock/PerlOnJava

rem Get the directory where this script is located
set SCRIPT_DIR=%~dp0

rem Launch Java
java -cp "%CLASSPATH%;%SCRIPT_DIR%target\perlonjava-3.0.0.jar" org.perlonjava.Main %*
