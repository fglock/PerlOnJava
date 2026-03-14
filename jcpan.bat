@echo off
rem jcpan - CPAN Client for PerlOnJava (Windows wrapper)
set SCRIPT_DIR=%~dp0
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%jcpan.pl" %*
