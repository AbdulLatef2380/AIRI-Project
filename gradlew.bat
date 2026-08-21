@echo off
setlocal

set APP_BASE_NAME=%~n0
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
    set JAVA_EXE=java.exe
)

"%JAVA_EXE%" -Xmx512m -Xms64m -classpath "%CLASSPATH%" "-Dorg.gradle.appname=%APP_BASE_NAME%" org.gradle.wrapper.GradleWrapperMain %*
set EXIT_CODE=%ERRORLEVEL%

endlocal & exit /b %EXIT_CODE%
