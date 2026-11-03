@echo off
@rem ===========================================================================
@rem  Dump the metadata inside a mod jar (check the license field etc.)
@rem
@rem       tools\inspect-jar.bat build\artifacts\sharedplayerdata-1.21.1-neoforge-1.0.0.jar
@rem
@rem  With no argument, checks every jar under build\artifacts\.
@rem
@rem  For NeoForge / Forge, "license" must be at the TOP of the metadata file,
@rem  before the first [[mods]] header. This script reports where it actually is.
@rem ===========================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0\.."

set "FILES=%~1"
if not defined FILES set "FILES=build\artifacts\*.jar"

where tar >nul 2>&1
if errorlevel 1 (
    echo tar.exe not found. On Windows 10 17063+ it ships with the OS.
    echo As a fallback, open the jar with 7-Zip and read the file manually.
    pause
    exit /b 1
)

set "FOUND=0"
for %%J in ("%FILES%") do (
    if exist "%%J" (
        set "FOUND=1"
        echo.
        echo ==================== %%~nxJ ====================
        for %%E in ("META-INF/neoforge.mods.toml" "META-INF/mods.toml" "fabric.mod.json") do (
            set "TMPF=%TEMP%\spd-inspect-%RANDOM%.tmp"
            tar -xOf "%%J" "%%~E" > "!TMPF!" 2>nul
            if exist "!TMPF!" (
                findstr /r /v "^$" "!TMPF!" >nul
                if not errorlevel 1 (
                    echo ----- %%~E -----
                    type "!TMPF!"
                    set "VL="
                    for /f "tokens=1 delims=:" %%N in ('findstr /n /b /l /c:"license" "!TMPF!"') do if not defined VL set "VL=%%N"
                    set "ML="
                    for /f "tokens=1 delims=:" %%N in ('findstr /n /b /l /c:"[[" "!TMPF!"') do if not defined ML set "ML=%%N"
                    if not defined VL (
                        echo   [!!] NO license field - the game will reject this jar
                    ) else if not defined ML (
                        echo   [OK] license present
                    ) else if !VL! GTR !ML! (
                        echo   [!!] license is INSIDE the [[mods]] section
                        echo        It must be at the top of the file, before [[mods]]
                    ) else (
                        echo   [OK] license is at the top level, before [[mods]]
                    )
                    echo.
                )
            )
            del /q "!TMPF!" >nul 2>&1
        )
    )
)

if "!FOUND!"=="0" (
    echo No jar found: %FILES%
    echo Usage: tools\inspect-jar.bat ^<path-to-jar^>
)
echo.
pause
