@echo off
rem jcpan - CPAN Client for PerlOnJava (Windows wrapper)
rem Runs the standard cpan script with jperl
set SCRIPT_DIR=%~dp0
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\cpan" %*
