@echo off
set SCRIPT_DIR=%~dp0
java -Xmx512m -cp "%CLASSPATH%;%SCRIPT_DIR%target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main %*
