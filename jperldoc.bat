@echo off
rem jperldoc - Perl documentation viewer for PerlOnJava (Windows wrapper)
rem Runs the standard perldoc script with jperl
set SCRIPT_DIR=%~dp0
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\perldoc" %*
