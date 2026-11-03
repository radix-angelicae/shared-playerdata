@echo off
@rem ===========================================================================
@rem  Run the core self-test only. No Gradle, no Minecraft needed.
@rem
@rem  core/ does not depend on any MC or loader class, so javac can
@rem  compile it directly. Use this to sanity-check the logic before
@rem  fighting with Gradle.
@rem
@rem      tools\selftest.bat
@rem ===========================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0\.."

set "OUT=%TEMP%\spd-selftest-%RANDOM%"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%" 2>nul

where javac >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javac not found. Install a JDK 17+ from https://adoptium.net/
    pause
    exit /b 1
)

@rem ---------------------------------------------------------------------------
@rem 先用静态检查过一遍：抓括号不配平、调用了没定义的方法、用了没 import 的类型。
@rem 这三类错误在三个版本里各出现过一次，都是编译阻断级。
@rem 有 python 就跑，没有也不影响后面的 javac。
@rem ---------------------------------------------------------------------------
where python >nul 2>&1
if not errorlevel 1 (
    echo [selftest] static check ...
    python tools\jcheck.py .
    if errorlevel 1 (
        echo.
        echo [selftest] static check reported ERRORS - fix them before compiling
        pause
        exit /b 1
    )
) else (
    where python3 >nul 2>&1
    if not errorlevel 1 (
        echo [selftest] static check ...
        python3 tools\jcheck.py .
        if errorlevel 1 (
            echo.
            echo [selftest] static check reported ERRORS - fix them before compiling
            pause
            exit /b 1
        )
    )
)

echo [selftest] compiling core ...
dir /s /b core\src\main\java\*.java > "%OUT%\sources.txt" 2>nul
javac -encoding UTF-8 -d "%OUT%\classes" @"%OUT%\sources.txt"
if errorlevel 1 (
    echo [selftest] FAILED to compile
    pause
    exit /b 1
)

echo [selftest] running ...
echo.
java -Dfile.encoding=UTF-8 -cp "%OUT%\classes" org.sharedplayerdata.core.SelfTest
set "RC=!ERRORLEVEL!"
echo.
if "!RC!"=="0" (
    echo [selftest] PASSED
) else (
    echo [selftest] FAILED ^(exit code !RC!^)
)

rmdir /s /q "%OUT%" >nul 2>&1
echo.
pause
exit /b !RC!
