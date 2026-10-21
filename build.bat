@echo off
rem Build the .spmod artifact.
rem 用项目内的 GRADLE_USER_HOME，绕开机器上把仓库改写到阿里云镜像的 init 脚本（会丢掉 JitPack）。
setlocal
set GRADLE=%USERPROFILE%\tools\gradle-9.8.1\bin\gradle.bat
if not exist "%GRADLE%" (
  echo [ERROR] gradle not found: %GRADLE%
  exit /b 1
)
set GRADLE_USER_HOME=%~dp0.gradle-home
call "%GRADLE%" --no-daemon buildAll %*
endlocal
