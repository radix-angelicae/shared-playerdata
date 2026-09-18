@echo off
@rem ===========================================================================
@rem  push.bat - commit and push this project to GitHub in one step.
@rem
@rem  Usage:
@rem      push.bat                  ask for a commit message, then push
@rem      push.bat "fix something"  use the given message without asking
@rem      push.bat --dry-run        show what would be committed, do nothing
@rem
@rem  Override with environment variables:
@rem      SPD_REPO_URL   default https://github.com/radix-angelicae/shared-playerdata.git
@rem      SPD_BRANCH     default main
@rem
@rem  Authentication (GitHub no longer accepts account passwords):
@rem      * HTTPS + Personal Access Token is the simplest.
@rem        Create one at GitHub -> Settings -> Developer settings ->
@rem        Personal access tokens -> Tokens (classic), tick "repo".
@rem        Git Credential Manager remembers it after the first push.
@rem      * SSH also works: set SPD_REPO_URL to
@rem        git@github.com:radix-angelicae/shared-playerdata.git
@rem
@rem  Two deliberate style choices in this file:
@rem      1. All messages are ASCII, because a Chinese Windows console can
@rem         mangle UTF-8 batch output depending on the active code page.
@rem      2. NO nested ( ) blocks anywhere. Inside a parenthesised block cmd
@rem         treats any ")" in an echo line as the end of the block, which
@rem         produces the confusing error "unexpected at this time".
@rem         Everything below uses flat goto labels instead.
@rem ===========================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if not defined SPD_REPO_URL set "SPD_REPO_URL=https://github.com/radix-angelicae/shared-playerdata.git"
if not defined SPD_BRANCH set "SPD_BRANCH=main"
@rem Set SPD_SKIP_CI=0 to stop appending "[skip ci]" to the commit message.
if not defined SPD_SKIP_CI set "SPD_SKIP_CI=1"

set "DRY_RUN=0"
set "MSG="
set "SKIP_SYNC=0"

@rem Git does NOT use the system proxy. If a direct connection fails we retry
@rem once through SPD_GIT_PROXY (default 127.0.0.1:10808, same port as
@rem gradle.properties). Set SPD_GIT_PROXY= to disable the fallback entirely.
@rem Once the proxy is proven to work, GIT_PROXY_OPTS is reused for push.
if not defined SPD_GIT_PROXY set "SPD_GIT_PROXY=http://127.0.0.1:10808"
set "GIT_PROXY_OPTS="

@rem ---------------------------------------------------------------- arguments
if "%~1"=="--dry-run" ( set "DRY_RUN=1" & shift )
if not "%~1"=="" set "MSG=%~1"

echo.
echo ===========================================================================
echo  Shared Player Data - push to GitHub
echo  repo  : %SPD_REPO_URL%
echo  branch: %SPD_BRANCH%
echo ===========================================================================
echo.

@rem ------------------------------------------------------------------ git check
where git >nul 2>&1
if not errorlevel 1 goto :have_git
echo [ERROR] git was not found in PATH.
echo         Install Git for Windows: https://git-scm.com/download/win
goto :fail
:have_git

@rem ------------------------------------------------------------------ init repo
if exist ".git" goto :have_repo
echo [git] no repository here yet - running git init ...
git init
if not errorlevel 1 goto :init_branch
echo [ERROR] git init failed
goto :fail
:init_branch
git branch -M %SPD_BRANCH%
goto :remote
:have_repo
echo [git] existing repository found

@rem ------------------------------------------------------------------ remote
:remote
git config --get remote.origin.url >nul 2>&1
if not errorlevel 1 goto :remote_ok
echo [git] adding remote origin ...
git remote add origin "%SPD_REPO_URL%"
if not errorlevel 1 goto :remote_ok
echo [ERROR] could not add remote
goto :fail
:remote_ok
echo [git] remote origin:
git config --get remote.origin.url

@rem ------------------------------------------------------------------ identity
git config user.name >nul 2>&1
if not errorlevel 1 goto :have_name
echo.
echo [git] user.name is not configured. Set it once with:
echo         git config --global user.name  "Your Name"
echo         git config --global user.email "you@example.com"
echo       Then run this script again.
goto :fail
:have_name

git config user.email >nul 2>&1
if not errorlevel 1 goto :have_email
echo.
echo [git] user.email is not configured. Set it once with:
echo         git config --global user.email "you@example.com"
echo       Then run this script again.
goto :fail
:have_email

@rem ------------------------------------------- align LICENSE with the remote
@rem  The GitHub repo already has its own LICENSE. If ours differs, merging
@rem  the two unrelated histories would conflict on that file. Adopt the
@rem  remote copy first so both sides match.
echo.
echo [sync] fetching origin/%SPD_BRANCH% ...
git fetch origin %SPD_BRANCH% >nul 2>&1
if not errorlevel 1 goto :fetched

@rem Direct connection failed. Retry through the proxy before giving up -
@rem this matters a lot on the very first push, where skipping the merge
@rem would make the later push fail on unrelated histories.
if "%SPD_GIT_PROXY%"=="" goto :fetch_failed
echo [sync] direct fetch failed - retrying through proxy %SPD_GIT_PROXY% ...
set "GIT_PROXY_OPTS=-c http.proxy=%SPD_GIT_PROXY% -c https.proxy=%SPD_GIT_PROXY%"
git %GIT_PROXY_OPTS% fetch origin %SPD_BRANCH% >nul 2>&1
if not errorlevel 1 goto :fetched

:fetch_failed
echo [sync] could not fetch. The remote is unreachable, or the branch does not
echo [sync] exist yet. Treating this as a first push - merge will be skipped.
echo.
echo [sync] If the network needs a proxy, either set SPD_GIT_PROXY:
echo            set SPD_GIT_PROXY=http://127.0.0.1:7890
echo            push.bat
echo [sync] or configure git once:
echo            git config --global http.proxy  http://127.0.0.1:10808
echo            git config --global https.proxy http://127.0.0.1:10808
set "SKIP_SYNC=1"
goto :sync_done
:fetched

git cat-file -e origin/%SPD_BRANCH%:LICENSE >nul 2>&1
if not errorlevel 1 goto :has_remote_license
goto :sync_done
:has_remote_license

git show origin/%SPD_BRANCH%:LICENSE > "%TEMP%\spd-LICENSE.tmp" 2>nul
if not exist "%TEMP%\spd-LICENSE.tmp" goto :sync_done
move /y "%TEMP%\spd-LICENSE.tmp" LICENSE >nul
echo [sync] LICENSE aligned with the copy already on GitHub
:sync_done

@rem ------------------------------------------------------------------ stage
echo.
git add -A
if not errorlevel 1 goto :staged
echo [ERROR] git add failed
goto :fail
:staged

git diff --cached --quiet
if errorlevel 1 goto :has_changes
echo [git] nothing staged - the working tree already matches the last commit.
goto :sync_and_push
:has_changes

echo [git] staged changes:
echo ---------------------------------------------------------------------------
@rem --no-pager: without this git pipes long output into "less", which stops
@rem the script dead on an "(END)" prompt waiting for a keypress.
git --no-pager diff --cached --stat
echo ---------------------------------------------------------------------------
echo.

if not "%DRY_RUN%"=="1" goto :ask_message
echo [dry-run] stopping here, nothing was committed or pushed.
goto :ok

@rem ------------------------------------------------------------------ message
:ask_message
if defined MSG goto :do_commit
set /p "MSG=commit message (press Enter for 'Update shared-playerdata'): "
if not defined MSG set "MSG=Update shared-playerdata"

:do_commit
echo.
@rem Append "[skip ci]". GitHub Actions reads this from the commit message and
@rem skips any push-triggered workflow. The repo currently has no push trigger
@rem (see .github/workflows/build.yml), so this is belt-and-braces: if push
@rem triggers are ever switched back on, ordinary commits still will not start
@rem a build. Set SPD_SKIP_CI=0 to leave the message untouched.
@rem Built as a plain SET outside any ( ) block, so a message containing
@rem parentheses cannot break the block.
if not "%SPD_SKIP_CI%"=="0" goto :with_skip
set "CMT=%MSG%"
goto :run_commit
:with_skip
set "CMT=%MSG% [skip ci]"
:run_commit
git --no-pager commit -m "%CMT%"
if not errorlevel 1 goto :committed
echo [ERROR] commit failed. If the message contains quotes, pass it as:
echo         push.bat "your message"
goto :fail
:committed

@rem ------------------------------------------------------------------ sync
@rem  The GitHub repo starts from its own commit, so the very first merge needs
@rem  --allow-unrelated-histories. Try a plain merge first (used every time
@rem  after the first), then fall back. Quoting the ref keeps cmd from
@rem  stumbling over the slash.
:sync_and_push
if "!SKIP_SYNC!"=="1" goto :do_push
echo.
echo [sync] merging with origin/%SPD_BRANCH% ...
git merge "origin/%SPD_BRANCH%" --no-edit >nul 2>&1
if not errorlevel 1 goto :do_push
git merge --abort >nul 2>&1
echo [sync] unrelated histories detected - retrying with --allow-unrelated-histories
git --no-pager merge "origin/%SPD_BRANCH%" --allow-unrelated-histories --no-edit
if not errorlevel 1 goto :do_push
echo [ERROR] could not merge with origin/%SPD_BRANCH%.
echo         Resolve the conflict by hand, then run this script again:
echo             git status
echo             git add thefile
echo             git commit
goto :fail

@rem ------------------------------------------------------------------ push
:do_push
echo.
echo [git] pushing to origin/%SPD_BRANCH% ...
@rem GIT_PROXY_OPTS is non-empty only when the direct fetch already failed and
@rem the proxy retry succeeded, so we reuse whatever worked.
git %GIT_PROXY_OPTS% --no-pager push -u origin %SPD_BRANCH%
if not errorlevel 1 goto :pushed
echo.
echo [ERROR] push failed. Most likely an authentication problem.
echo   * HTTPS: GitHub needs a Personal Access Token, not your password.
echo           GitHub -^> Settings -^> Developer settings -^>
echo           Personal access tokens -^> Tokens, tick "repo".
echo   * SSH:   set SPD_REPO_URL to
echo           git@github.com:radix-angelicae/shared-playerdata.git
echo   * If the remote rejected a non-fast-forward update, run:
echo           git pull --rebase origin %SPD_BRANCH%
goto :fail
:pushed
echo.
echo [git] pushed.

:ok
echo.
echo ===========================================================================
echo  Done.
echo  Repo: %SPD_REPO_URL%
echo ===========================================================================
echo.
pause
exit /b 0

:fail
echo.
echo ===========================================================================
echo  Failed - see the messages above.
echo ===========================================================================
echo.
pause
exit /b 1
