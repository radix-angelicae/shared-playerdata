@echo off
@rem ===========================================================================
@rem  Shared Player Data - one-click build for Windows
@rem
@rem  Double click this file, or run from a terminal:
@rem      build.bat                    build every combination in matrix.properties
@rem      build.bat 1.20.1            build 1.20.1 (all loaders)
@rem      build.bat 1.20.1 fabric     build 1.20.1 + Fabric only
@rem      build.bat 1.20.1 1.21.1     build both versions (all their loaders)
@rem      build.bat fabric            build Fabric across every version
@rem      build.bat --list            only list what would be built
@rem      build.bat --update-versions refresh toolchain versions from Maven first
@rem      build.bat --wrapper         generate the standard gradlew / gradlew.bat
@rem      build.bat --no-mirrors      do not use the mirror init script
@rem      build.bat --stacktrace      full stack traces on failure
@rem      build.bat --no-pause        never wait for a key at the end
@rem
@rem  Requirements: JDK 17 or newer. Gradle is downloaded automatically into
@rem  gradle-dist\ on first run (about 130 MB per Gradle version, one time).
@rem
@rem  All downloads go through the proxy at 127.0.0.1:10808, which is configured
@rem  in gradle.properties (systemProp.*) and passed to the Gradle downloader.
@rem  build.bat probes that port first and aborts with a clear message when the
@rem  proxy is down; --no-proxy builds with no proxy at all.
@rem ===========================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

@rem Gradle used only to run the tools/matrix parser (bootstrap, not used for building)
set "BOOT_GRADLE=8.10"
@rem Proxy: keep in sync with the systemProp.* entries in gradle.properties.
@rem Change these two lines to use another port; set SPD_PROXY= to disable.
set "PROXY_HOST=127.0.0.1"
set "PROXY_PORT=10808"
if not defined SPD_PROXY set "SPD_PROXY=http://%PROXY_HOST%:%PROXY_PORT%"
set "DO_UPDATE=0"
set "USE_MIRRORS=0"
set "DO_WRAPPER=0"
set "DO_LIST=0"
set "NO_PAUSE=0"
set "DO_STOP=1"
set "STOP_ONLY=0"
@rem --no-proxy: skip the 127.0.0.1:10808 proxy. Used by CI, which has direct
@rem internet access. It does NOT touch gradle.properties (see the workflow).
set "NO_PROXY=0"
@rem Gradle versions actually used this run; needed by the final --stop
set "USED_GRADLES="
set "EXTRA_ARGS="
set "FILTER_MC="
set "FILTER_LOADER="

@rem ---------------------------------------------------------------- arguments
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--update-versions" ( set "DO_UPDATE=1" & shift & goto :parse )
if /i "%~1"=="--stacktrace"      ( set "EXTRA_ARGS=%EXTRA_ARGS% --stacktrace" & shift & goto :parse )
if /i "%~1"=="--info"            ( set "EXTRA_ARGS=%EXTRA_ARGS% --info" & shift & goto :parse )
if /i "%~1"=="--no-mirrors"      ( set "USE_MIRRORS=0" & shift & goto :parse )
if /i "%~1"=="--mirrors"         ( set "USE_MIRRORS=1" & shift & goto :parse )
if /i "%~1"=="--wrapper"         ( set "DO_WRAPPER=1" & shift & goto :parse )
if /i "%~1"=="--list"            ( set "DO_LIST=1" & shift & goto :parse )
if /i "%~1"=="--no-pause"        ( set "NO_PAUSE=1" & shift & goto :parse )
if /i "%~1"=="--stop"            ( set "STOP_ONLY=1" & shift & goto :parse )
if /i "%~1"=="--no-stop"         ( set "DO_STOP=0" & shift & goto :parse )
if /i "%~1"=="--no-proxy"        ( set "NO_PROXY=1" & shift & goto :parse )
if /i "%~1"=="-h" ( call :usage & goto :end_ok )
if /i "%~1"=="--help" ( call :usage & goto :end_ok )

@rem ---------------------------------------------------------------------------
@rem Position arguments are classified by meaning, not by order:
@rem   fabric / forge / neoforge  -> loader filter
@rem   anything else              -> an MC version, and you may list several
@rem
@rem So all of these work:
@rem     build.bat 1.20.1 fabric        one version, one loader
@rem     build.bat 1.20.1 1.21.1        two versions, all their loaders
@rem     build.bat 1.20.1 1.21.1 fabric two versions, Fabric only
@rem     build.bat fabric               Fabric across every version
@rem
@rem Kept flat (no nested parentheses) on purpose: a ")" inside a block would
@rem be read as the end of the block, which is what produced the old
@rem "unexpected at this time" failure.
@rem ---------------------------------------------------------------------------
set "ISLOADER=0"
if /i "%~1"=="fabric"   set "ISLOADER=1"
if /i "%~1"=="forge"    set "ISLOADER=1"
if /i "%~1"=="neoforge" set "ISLOADER=1"
if "!ISLOADER!"=="1" goto :arg_loader
if not defined FILTER_MC goto :arg_first_mc
set "FILTER_MC=!FILTER_MC!,%~1"
goto :arg_next
:arg_first_mc
set "FILTER_MC=%~1"
goto :arg_next
:arg_loader
set "FILTER_LOADER=%~1"
goto :arg_next
:arg_next
shift
goto :parse
:parsed

@rem Honour --no-proxy: clear the download proxy. The 4 systemProp.* lines in
@rem gradle.properties are neutralised by tools\proxy.gradle (see PROXY_INIT
@rem below) - clearing only SPD_PROXY here would leave them active and the
@rem build would still hit 127.0.0.1:10808.
if "!NO_PROXY!"=="1" set "SPD_PROXY="

@rem ---------------------------------------------------------------- proxy control
@rem Optional overrides, applied to BOTH the Gradle distribution download and
@rem the dependency downloads, so a different port needs no file edits.
if defined SPD_PROXY_HOST set "PROXY_HOST=!SPD_PROXY_HOST!"
if defined SPD_PROXY_PORT set "PROXY_PORT=!SPD_PROXY_PORT!"
if defined SPD_PROXY_HOST set "SPD_PROXY=http://!PROXY_HOST!:!PROXY_PORT!"
if defined SPD_PROXY_PORT set "SPD_PROXY=http://!PROXY_HOST!:!PROXY_PORT!"

set PROXY_INIT=-I "%~dp0tools\proxy.gradle"
set "PROXY_ARGS="
if "!NO_PROXY!"=="1" (
    set "PROXY_ARGS=-Dspd.proxyMode=off"
) else (
    if defined SPD_PROXY_HOST set "PROXY_ARGS=!PROXY_ARGS! -Dspd.proxyHost=!SPD_PROXY_HOST!"
    if defined SPD_PROXY_PORT set "PROXY_ARGS=!PROXY_ARGS! -Dspd.proxyPort=!SPD_PROXY_PORT!"
)

@rem Probe the proxy BEFORE downloading Gradle itself. gradle.properties routes
@rem EVERY download through 127.0.0.1:10808; when that port is not listening,
@rem ForgeGradle / Loom retry dozens of artifacts and print a wall of
@rem "Connection refused" stacks, which looks exactly like a hang.
@rem --stop / --list need no network, --no-proxy explicitly opts out.
if "%STOP_ONLY%"=="1" goto :proxy_ready
if "%DO_LIST%"=="1" goto :proxy_ready
if "!NO_PROXY!"=="1" goto :proxy_ready
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\check-proxy.ps1" -TargetHost "%PROXY_HOST%" -Port %PROXY_PORT% -TimeoutMs 1200 >nul 2>&1
if not errorlevel 1 goto :proxy_ok
echo.
echo [proxy] ERROR: cannot reach %PROXY_HOST%:%PROXY_PORT%
echo [proxy] gradle.properties sends EVERY download through this proxy.
echo [proxy] With it down Gradle prints a wall of Connection refused stacks
echo [proxy] and appears to hang, so the build is aborted here.
echo.
echo [proxy] Pick one:
echo [proxy]   1. Start your proxy client, then run build.bat again
echo [proxy]   2. Build without the proxy:  build.bat --no-proxy
echo [proxy]   3. Use a different port:    set SPD_PROXY_PORT=7890
echo.
goto :end_fail
:proxy_ok
echo [proxy] reachable: %PROXY_HOST%:%PROXY_PORT%
:proxy_ready

@rem ---------------------------------------------------------------- sanity checks
if not exist matrix.properties (
    echo [ERROR] matrix.properties not found in %~dp0
    goto :end_fail
)
set "MATRIX_SIZE=0"
for %%S in ("matrix.properties") do set "MATRIX_SIZE=%%~zS"
if !MATRIX_SIZE! LSS 100 (
    echo [ERROR] matrix.properties is only !MATRIX_SIZE! bytes - it looks empty or truncated.
    echo         Re-extract the project archive, or copy matrix.properties from a fresh download.
    goto :end_fail
)
if not exist tools\matrix\build.gradle (
    echo [ERROR] tools\matrix\build.gradle is missing. Re-extract the archive.
    goto :end_fail
)

@rem ---------------------------------------------------------------- java check
java -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo [ERROR] Java was not found on PATH.
    echo         Install JDK 17 or newer, e.g. https://adoptium.net/
    echo         then run this file again.
    echo.
    goto :end_fail
)

@rem ---------------------------------------------------------------- bootstrap gradle
call :ensureGradle %BOOT_GRADLE%
if errorlevel 1 goto :end_fail
set "BOOT_BIN=!CUR_GRADLE_BIN!"
echo [gradle] bootstrap: !BOOT_BIN!

@rem Cleanup-only mode: stop every Gradle daemon, then exit without building
if "%STOP_ONLY%"=="1" (
    echo.
    echo [gradle] stopping every Gradle daemon found in gradle-dist\ ...
    call :stopDaemons all
    set "DO_STOP=0"
    echo [gradle] done.
    goto :end_ok
)

@rem A running daemon does not pick up changes in gradle.properties (proxy
@rem settings are JVM system properties), so stop it first. Fails silently
@rem when no daemon is running, which is fine.
echo [gradle] stopping stale daemons so the proxy settings take effect ...
call "!BOOT_BIN!" --stop >nul 2>&1

@rem ---------------------------------------------------------------- init script
set "INIT_ARG="
if "%USE_MIRRORS%"=="1" (
    if exist "%~dp0tools\mirrors.gradle" set INIT_ARG=-I "%~dp0tools\mirrors.gradle"
)

@rem ---------------------------------------------------------------- update versions
if "%DO_UPDATE%"=="1" (
    echo [matrix] refreshing toolchain versions ...
    where python >nul 2>&1
    if not errorlevel 1 ( python tools\update_versions.py ) else ( echo [matrix] python not found, skipped )
)

@rem ---------------------------------------------------------------- wrapper task
if "%DO_WRAPPER%"=="1" (
    echo [gradle] generating the standard wrapper ...
    call "!BOOT_BIN!" wrapper --gradle-version %BOOT_GRADLE% --distribution-type bin --console=plain !INIT_ARG!
    if errorlevel 1 goto :end_fail
    echo [gradle] done. You can now use gradlew.bat directly.
    goto :end_ok
)

@rem ---------------------------------------------------------------- read the matrix
@rem  Parsed by a tiny plugin-free Gradle build (tools\matrix) instead of findstr,
@rem  so LF / CRLF / stray CR line endings all behave the same.
@rem  Output rows:  mc|loader|gradleVersion|ok
set "MATRIX_TXT=%TEMP%\spd-matrix-%RANDOM%%RANDOM%.txt"
set "MATRIX_ARGS="
if defined FILTER_MC     set "MATRIX_ARGS=!MATRIX_ARGS! -PmcFilter=!FILTER_MC!"
if defined FILTER_LOADER set "MATRIX_ARGS=!MATRIX_ARGS! -PloaderFilter=!FILTER_LOADER!"

echo [matrix] reading matrix.properties ...
call "!BOOT_BIN!" -p tools\matrix -q printMatrix --console=plain !MATRIX_ARGS! > "%MATRIX_TXT%"
if errorlevel 1 (
    echo [ERROR] could not read the version matrix. See the message above.
    if exist "%MATRIX_TXT%" del /q "%MATRIX_TXT%" >nul 2>&1
    goto :end_fail
)
if not exist "%MATRIX_TXT%" (
    echo [ERROR] the matrix parser produced no output.
    goto :end_fail
)

@rem ---------------------------------------------------------------- count combos
set "TOTAL=0"
for /f "usebackq tokens=1,2,3,4 delims=|" %%A in ("%MATRIX_TXT%") do set /a TOTAL+=1

if "!TOTAL!"=="0" (
    echo [ERROR] the matrix is empty - nothing to build.
    echo         Check mc.versions and the ^<version^>.loaders lines in matrix.properties
    del /q "%MATRIX_TXT%" >nul 2>&1
    goto :end_fail
)

echo [matrix] !TOTAL! combination(s) to build:
for /f "usebackq tokens=1,2,3,4 delims=|" %%A in ("%MATRIX_TXT%") do echo            %%A / %%B   (Gradle %%C)

if "%DO_LIST%"=="1" (
    del /q "%MATRIX_TXT%" >nul 2>&1
    goto :end_ok
)

@rem ---------------------------------------------------------------- build loop
if not exist build\artifacts mkdir build\artifacts

set "BUILT=0"
set "FAILED="
set "INDEX=0"

for /f "usebackq tokens=1,2,3,4 delims=|" %%A in ("%MATRIX_TXT%") do (
    set /a INDEX+=1
    echo.
    echo ==^> [!INDEX!/!TOTAL!] Building %%A / %%B with Gradle %%C

    @rem Remove last run's artifact first: if this build fails we must not be
    @rem left with an old jar that looks like a fresh one.
    if exist build\artifacts\sharedplayerdata-%%A-%%B-*.jar (
        del /q build\artifacts\sharedplayerdata-%%A-%%B-*.jar >nul 2>&1
        echo     removed stale artifact from build\artifacts\
    )

    call :ensureGradle %%C
    if errorlevel 1 (
        echo     [FAILED] %%A / %%B - could not obtain Gradle %%C
        set "FAILED=!FAILED! %%A/%%B"
    ) else (
        call "!CUR_GRADLE_BIN!" ":%%B:build" -PmcVersion=%%A -PtargetLoader=%%B --console=plain !INIT_ARG! !PROXY_INIT! !PROXY_ARGS! !EXTRA_ARGS!
        if errorlevel 1 (
            echo     [FAILED] %%A / %%B ^(continuing with the next one^)
            set "FAILED=!FAILED! %%A/%%B"
        ) else (
            set "GOTJAR=0"
            for %%J in ("%%B\build\libs\sharedplayerdata-%%A-%%B-*.jar") do (
                if exist "%%J" (
                    set "JN=%%~nxJ"
                    set "SKIPJAR=0"
                    if not "!JN:-sources=!"=="!JN!" set "SKIPJAR=1"
                    if not "!JN:-dev=!"=="!JN!" set "SKIPJAR=1"
                    if "!SKIPJAR!"=="0" (
                        copy /y "%%J" build\artifacts\ >nul
                        echo     artifact: build\artifacts\%%~nxJ
                        set "GOTJAR=1"
                    )
                )
            )
            if "!GOTJAR!"=="0" (
                echo     [WARN] build reported success but no jar was found
                echo            expected: %%B\build\libs\sharedplayerdata-%%A-%%B-*.jar
            ) else (
                call :verifyArtifact "%%B\build\libs\sharedplayerdata-%%A-%%B-*.jar" %%B
            )
            set /a BUILT+=1
        )
    )
)

del /q "%MATRIX_TXT%" >nul 2>&1

echo.
echo ===========================================================================
echo Build finished. !BUILT! of !TOTAL! combination(s) succeeded.
if defined FAILED (
    echo Failed:%FAILED%
    echo.
    echo Usual causes:
    echo   * wrong Forge / NeoForge / Loom version in matrix.properties
    echo     - fix with:  build.bat --update-versions
    echo     - or look it up at https://files.minecraftforge.net/ and https://neoforged.net/
    echo   * Gradle version too old for that Loom/ForgeGradle: add e.g.
    echo     1.21.5.gradle=8.13   to matrix.properties
    echo   * JDK too old ^(need 17+^), or a dependency could not be downloaded
    echo     - retry with:  build.bat --no-mirrors
    echo   * Connection refused / Failed download after 3 attempts:
    echo     - the proxy at 127.0.0.1:10808 is down; start it, or use
    echo       build.bat --no-proxy, or set SPD_PROXY_PORT=your port
    echo   * "No matching toolchains found": install JDK 17 ^(for 1.19-1.20.4^)
    echo     and JDK 21 ^(for 1.20.6-1.21.x^), or enable the Foojay resolver
    echo     ^(see the comment in settings.gradle^)
    echo   * Fabric: if you saw "Plugin [id: 'fabric-loom', version: 'X'] was not
    echo     found", that exact Loom version does not exist. Edit the matching
    echo     [mc].loom line in matrix.properties and rebuild.
    goto :end_fail
)
if "!BUILT!"=="0" (
    echo [ERROR] not a single combination was built, yet no failure was recorded.
    echo         This should not happen - please report it with the full log above.
    goto :end_fail
)
echo Artifacts are in build\artifacts\
goto :end_ok

@rem ---------------------------------------------------------------- subroutines

@rem ensureGradle <version>  -> sets CUR_GRADLE_BIN, errorlevel 1 on failure
:ensureGradle
setlocal
set "GV=%~1"
if not defined GV set "GV=%BOOT_GRADLE%"
set "GB=%~dp0gradle-dist\gradle-%GV%\bin\gradle.bat"
if exist "%GB%" (
    endlocal & set "CUR_GRADLE_BIN=%GB%" & set "USED_GRADLES=%USED_GRADLES% %GV%"
    exit /b 0
)
echo [gradle] Gradle %GV% not present, downloading into gradle-dist\ ...
if "!NO_PROXY!"=="1" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\fetch-gradle.ps1" -Version %GV% -NoProxy
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\fetch-gradle.ps1" -Version %GV% -Proxy "%SPD_PROXY%"
)
if errorlevel 1 (
    endlocal
    exit /b 1
)
if not exist "%GB%" (
    endlocal
    exit /b 1
)
endlocal & set "CUR_GRADLE_BIN=%GB%" & set "USED_GRADLES=%USED_GRADLES% %GV%"
exit /b 0

@rem verifyArtifact <jarPath> <loader>
@rem   NeoForge / Forge require "license" at the TOP of the metadata file,
@rem   before the first [[mods]] header. Inside [[mods]] it is ignored and the
@rem   game fails with "Missing license". Check both presence and position.
:verifyArtifact
set "VFILE="
for %%V in ("%~1") do set "VFILE=%%~fV"
if not defined VFILE exit /b 0
if not exist "!VFILE!" exit /b 0
set "VPATH="
if /i "%~2"=="neoforge" set "VPATH=META-INF/neoforge.mods.toml"
if /i "%~2"=="forge"    set "VPATH=META-INF/mods.toml"
if /i "%~2"=="fabric"   set "VPATH=fabric.mod.json"
if not defined VPATH exit /b 0
where tar >nul 2>&1
if errorlevel 1 exit /b 0
set "VTMP=%TEMP%\spd-verify-%RANDOM%%RANDOM%.tmp"
tar -xOf "!VFILE!" "!VPATH!" > "%VTMP%" 2>nul
if not exist "%VTMP%" exit /b 0
findstr /r /v "^$" "%VTMP%" >nul
if errorlevel 1 (
    del /q "%VTMP%" >nul 2>&1
    exit /b 0
)

if /i "%~2"=="fabric" (
    findstr /i /c:"license" "%VTMP%" >nul
    if errorlevel 1 (
        echo     [WARN] fabric.mod.json has NO license field
    ) else (
        echo     verified: fabric.mod.json has license
    )
    del /q "%VTMP%" >nul 2>&1
    exit /b 0
)

set "VLINE="
for /f "tokens=1 delims=:" %%N in ('findstr /n /b /l /c:"license" "%VTMP%"') do if not defined VLINE set "VLINE=%%N"
set "MLINE="
for /f "tokens=1 delims=:" %%N in ('findstr /n /b /l /c:"[[" "%VTMP%"') do if not defined MLINE set "MLINE=%%N"

if not defined VLINE (
    echo     [WARN] !VPATH! has NO license field - the game will reject this jar
) else if not defined MLINE (
    echo     verified: top-level license in !VPATH!
) else if !VLINE! GTR !MLINE! (
    echo     [WARN] license is inside the [[mods]] section
    echo           It must be at the top of the file, before [[mods]]
) else (
    echo     verified: top-level license in !VPATH!
)
del /q "%VTMP%" >nul 2>&1
exit /b 0

@rem stopDaemons used|all
@rem   used = stop only the versions used by this build
@rem   all  = stop every version present in gradle-dist (cleans up leftovers)
:stopDaemons
set "STOP_LIST=!USED_GRADLES!"
if /i "%~1"=="all" (
    for /d %%D in ("%~dp0gradle-dist\gradle-*") do set "STOP_LIST=!STOP_LIST! %%~nxD"
)
if "!STOP_LIST!"=="" exit /b 0
set "STOPPED= "
for %%G in (!STOP_LIST!) do (
    set "GVX=%%G"
    set "GVX=!GVX:gradle-=!"
    set "ALREADY=0"
    for %%S in (!STOPPED!) do if "%%S"=="!GVX!" set "ALREADY=1"
    if "!ALREADY!"=="0" (
        set "STOPPED=!STOPPED!!GVX! "
        set "SB=%~dp0gradle-dist\gradle-!GVX!\bin\gradle.bat"
        if exist "!SB!" (
            echo [gradle] stopping Gradle !GVX! daemons ...
            call "!SB!" --stop --console=plain
        )
    )
)
exit /b 0

:usage
echo.
echo   build.bat                    build everything in matrix.properties
echo   build.bat 1.20.1            build 1.20.1, all loaders
echo   build.bat 1.20.1 fabric     build 1.20.1 + Fabric only
echo   build.bat 1.20.1 1.21.1     build both versions, all their loaders
echo   build.bat 1.20.1 1.21.1 fabric   both versions, Fabric only
echo   build.bat fabric            build Fabric across every version
echo   build.bat --list            only list the combinations, do not build
echo   build.bat --update-versions refresh toolchain versions from Maven first
echo   build.bat --wrapper         generate the standard gradlew / gradlew.bat
echo   build.bat --no-mirrors      use official repos (this is now the default)
echo   build.bat --mirrors         use the China mirror init script instead
echo   build.bat --stacktrace      full stack traces on failure
echo   build.bat --no-stop         keep the Gradle daemons running afterwards
echo   build.bat --no-proxy        build with NO proxy at all (direct connection)
echo   build.bat --stop            stop every Gradle daemon and exit (no build)
echo.
echo   Proxy: gradle.properties routes all downloads through 127.0.0.1:10808.
echo   Override without editing files:  set SPD_PROXY_HOST=127.0.0.1
echo                                   set SPD_PROXY_PORT=7890
echo.
exit /b 0

:end_fail
set "EXITCODE=1"
goto :finish

:end_ok
set "EXITCODE=0"
goto :finish

:finish
@rem  Gradle daemons idle for 3 hours by default; stop them so no java.exe is
@rem  left behind after the script exits.
if "%DO_STOP%"=="1" call :stopDaemons used

@rem  Only wait for a key when the file was launched by double click (explorer
@rem  passes /c to cmd.exe); running it from a terminal should return immediately.
@rem  --no-pause forces the same behaviour, which is what CI relies on.
if "%NO_PAUSE%"=="1" exit /b %EXITCODE%
echo %CMDCMDLINE% | findstr /i /c:"/c" >nul
if errorlevel 1 exit /b %EXITCODE%
pause
exit /b %EXITCODE%
