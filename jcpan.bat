@echo off
rem jcpan - CPAN Client for PerlOnJava (Windows wrapper)
rem Runs the standard cpan script with jperl
rem Supports -j N for parallel test execution:
rem   jcpan -j 4 -t Module::Name
set SCRIPT_DIR=%~dp0

rem Parse -j option for parallel test jobs
set "JCPAN_ARGS="
:parse_args
if "%~1"=="" goto run
if "%~1"=="-j" (
    set "HARNESS_OPTIONS=j%~2"
    shift
    shift
    goto parse_args
)
set "JCPAN_ARGS=%JCPAN_ARGS% %1"
shift
goto parse_args

:run
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\cpan" %JCPAN_ARGS%
