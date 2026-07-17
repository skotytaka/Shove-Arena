@echo off
rem Downloads gradle-wrapper.jar (needed only for command-line gradlew usage).
rem Android Studio / IntelliJ IDEA do NOT need this - they sync from
rem gradle-wrapper.properties directly.
echo Downloading gradle-wrapper.jar ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
if exist gradle\wrapper\gradle-wrapper.jar (
  echo OK: gradle-wrapper.jar installed.
) else (
  echo FAILED. See README.md - or just open the project in Android Studio.
)
pause
