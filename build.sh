#!/usr/bin/env bash
# =============================================================================
#  Shared Player Data - one-click build for Linux / macOS
#
#      ./build.sh                    build every combination in matrix.properties
#      ./build.sh 1.20.1            build 1.20.1 (all loaders)
#      ./build.sh 1.20.1 fabric     build 1.20.1 + Fabric only
#      ./build.sh 1.20.1 1.21.1     build both versions (all their loaders)
#      ./build.sh fabric            build Fabric across every version
#      ./build.sh --list            only list what would be built
#      ./build.sh --update-versions refresh toolchain versions from Maven first
#      ./build.sh --wrapper         generate the standard gradlew
#      ./build.sh --mirrors         use the China mirror init script
#      ./build.sh --stacktrace      full stack traces on failure
#      ./build.sh --stop            stop every Gradle daemon and exit
#
#  Requirements: JDK 17 or newer. Gradle is downloaded automatically into
#  gradle-dist/ on first run (about 130 MB per Gradle version, one time).
#
#  All downloads go through the proxy at 127.0.0.1:10808, which is configured
#  in gradle.properties (systemProp.*) and passed to the Gradle downloader.
#  Override with SPD_PROXY, or disable with SPD_PROXY= (or --no-proxy).
#
#  NOTE: "set -e" is deliberately NOT used. One combination failing must not
#  abort the whole loop, and a bare "grep" that matches nothing would silently
#  kill the script under -e. Every exit code is checked explicitly instead.
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")" || exit 1

BOOT_GRADLE="8.10"
PROXY_HOST="127.0.0.1"
PROXY_PORT="10808"
: "${SPD_PROXY:=http://$PROXY_HOST:$PROXY_PORT}"

DO_UPDATE=0
USE_MIRRORS=0
DO_WRAPPER=0
DO_LIST=0
DO_STOP=1
STOP_ONLY=0
NO_PROXY=0
EXTRA_ARGS=""
FILTER_MC=""
FILTER_LOADER=""
# Gradle versions actually used this run; needed by the final --stop
USED_GRADLES=()

# ---------------------------------------------------------------- helpers
usage() {
    cat <<'EOF'

  ./build.sh                    build everything in matrix.properties
  ./build.sh 1.20.1            build 1.20.1, all loaders
  ./build.sh 1.20.1 fabric     build 1.20.1 + Fabric only
  ./build.sh 1.20.1 1.21.1     build both versions, all their loaders
  ./build.sh 1.20.1 1.21.1 fabric   both versions, Fabric only
  ./build.sh fabric            build Fabric across every version
  ./build.sh --list            only list the combinations, do not build
  ./build.sh --update-versions refresh toolchain versions from Maven first
  ./build.sh --wrapper         generate the standard gradlew
  ./build.sh --mirrors         use the China mirror init script
  ./build.sh --no-mirrors      use official repos (this is the default)
  ./build.sh --stacktrace      full stack traces on failure
  ./build.sh --no-stop         keep the Gradle daemons running afterwards
  ./build.sh --no-proxy        download Gradle directly (skip the local proxy)
  ./build.sh --stop            stop every Gradle daemon and exit (no build)

EOF
}

log_fail() {
    cat <<'EOF'

Usual causes:
  * wrong Forge / NeoForge / Loom version in matrix.properties
    - fix with:  ./build.sh --update-versions
    - or look it up at https://files.minecraftforge.net/ and https://neoforged.net/
  * Gradle version too old for that Loom/ForgeGradle: add e.g.
      1.21.5.gradle=8.13   to matrix.properties
  * JDK too old (need 17+), or a dependency could not be downloaded
    - retry with:  ./build.sh --no-mirrors
  * "No matching toolchains found": install JDK 17 (for 1.19-1.20.4)
    and JDK 21 (for 1.20.6-1.21.x), or enable the Foojay resolver
    (see the comment in settings.gradle)
  * Fabric: if you saw "Plugin [id: 'fabric-loom', version: 'X'] was not
    found", that exact Loom version does not exist. Edit the matching
    [mc].loom line in matrix.properties and rebuild.
EOF
}

# ensureGradle <version> -> sets CUR_GRADLE_BIN, returns 1 on failure
ensureGradle() {
    local gv="${1:-$BOOT_GRADLE}"
    local gb="$PWD/gradle-dist/gradle-$gv/bin/gradle"
    if [ -x "$gb" ]; then
        CUR_GRADLE_BIN="$gb"
        USED_GRADLES+=("$gv")
        return 0
    fi
    echo "[gradle] Gradle $gv not present, downloading into gradle-dist/ ..."
    if [ "$NO_PROXY" = "1" ]; then
        bash "$PWD/tools/fetch-gradle.sh" -Version "$gv" -NoProxy
    else
        bash "$PWD/tools/fetch-gradle.sh" -Version "$gv" -Proxy "$SPD_PROXY"
    fi
    if [ $? -ne 0 ] || [ ! -x "$gb" ]; then
        return 1
    fi
    CUR_GRADLE_BIN="$gb"
    USED_GRADLES+=("$gv")
    return 0
}

# verifyArtifact <jarPath> <loader>
#   Forge / NeoForge require "license" at the TOP of the metadata file, before
#   the first [[mods]] header. Inside [[mods]] it is ignored and the game fails
#   with "Missing license". Check both presence and position.
verifyArtifact() {
    local jar="$1" loader="$2"
    [ -f "$jar" ] || return 0

    local vpath=""
    case "$loader" in
        neoforge) vpath="META-INF/neoforge.mods.toml" ;;
        forge)    vpath="META-INF/mods.toml" ;;
        fabric)   vpath="fabric.mod.json" ;;
        *)        return 0 ;;
    esac

    command -v unzip >/dev/null 2>&1 || return 0
    local content
    content="$(unzip -p "$jar" "$vpath" 2>/dev/null)"
    [ -n "$content" ] || return 0

    if [ "$loader" = "fabric" ]; then
        if printf '%s\n' "$content" | grep -qi 'license'; then
            echo "    verified: fabric.mod.json has license"
        else
            echo "    [WARN] fabric.mod.json has NO license field"
        fi
        return 0
    fi

    local vline mline
    vline="$(printf '%s\n' "$content" | grep -n '^license' | head -1 | cut -d: -f1)"
    mline="$(printf '%s\n' "$content" | grep -n '^\[\['   | head -1 | cut -d: -f1)"

    if [ -z "$vline" ]; then
        echo "    [WARN] $vpath has NO license field - the game will reject this jar"
    elif [ -z "$mline" ]; then
        echo "    verified: top-level license in $vpath"
    elif [ "$vline" -gt "$mline" ]; then
        echo "    [WARN] license is inside the [[mods]] section"
        echo "           It must be at the top of the file, before [[mods]]"
    else
        echo "    verified: top-level license in $vpath"
    fi
}

# stopDaemons used|all
stopDaemons() {
    local list=()
    if [ "$1" = "all" ]; then
        for d in gradle-dist/gradle-*; do
            [ -d "$d" ] && list+=("$(basename "$d")")
        done
    fi
    for v in "${USED_GRADLES[@]:-}"; do
        [ -n "$v" ] && list+=("gradle-$v")
    done

    local seen=" "
    for d in "${list[@]:-}"; do
        [ -n "$d" ] || continue
        local gv="${d#gradle-}"
        case "$seen" in *" $gv "*) continue ;; esac
        seen="$seen$gv "
        local sb="gradle-dist/gradle-$gv/bin/gradle"
        if [ -x "$sb" ]; then
            echo "[gradle] stopping Gradle $gv daemons ..."
            "$sb" --stop --console=plain >/dev/null 2>&1
        fi
    done
}

# Gradle daemons idle for 3 hours by default. Stop them on exit so no java
# process is left behind - including on Ctrl+C.
on_exit() {
    if [ "$DO_STOP" = "1" ]; then
        stopDaemons used
    fi
}
trap on_exit EXIT

# ---------------------------------------------------------------- arguments
while [ $# -gt 0 ]; do
    case "$1" in
        --update-versions) DO_UPDATE=1 ;;
        --stacktrace)      EXTRA_ARGS="$EXTRA_ARGS --stacktrace" ;;
        --info)            EXTRA_ARGS="$EXTRA_ARGS --info" ;;
        --no-mirrors)      USE_MIRRORS=0 ;;
        --mirrors)         USE_MIRRORS=1 ;;
        --wrapper)         DO_WRAPPER=1 ;;
        --list)            DO_LIST=1 ;;
        --stop)            STOP_ONLY=1 ;;
        --no-stop)         DO_STOP=0 ;;
        --no-proxy)        NO_PROXY=1 ;;
        --no-pause)        ;;              # batch-only, accepted for CI parity
        -h|--help)         usage; exit 0 ;;
        -*)
            echo "Unknown argument: $1"
            echo "Run './build.sh --help' for the list."
            exit 1
            ;;
        *)
            # Position arguments are classified by meaning, not by order:
            #   fabric / forge / neoforge -> loader filter
            #   anything else             -> an MC version (several allowed)
            # So "1.20.1 1.21.1" builds both versions, and "1.20.1 fabric"
            # still means one version + one loader.
            case "$1" in
                fabric|forge|neoforge)
                    FILTER_LOADER="$1"
                    ;;
                *)
                    if [ -z "$FILTER_MC" ]; then
                        FILTER_MC="$1"
                    else
                        FILTER_MC="$FILTER_MC,$1"
                    fi
                    ;;
            esac
            ;;
    esac
    shift
done

# --no-proxy: skip the download proxy. gradle.properties still has its own
# systemProp.* entries; CI strips those separately.
[ "$NO_PROXY" = "1" ] && SPD_PROXY=""

# ---------------------------------------------------------------- sanity checks
if [ ! -f matrix.properties ]; then
    echo "[ERROR] matrix.properties not found in $PWD"
    exit 1
fi
size=$(wc -c < matrix.properties | tr -d ' ')
if [ "${size:-0}" -lt 100 ]; then
    echo "[ERROR] matrix.properties is only ${size} bytes - it looks empty or truncated."
    echo "        Re-extract the project archive, or copy matrix.properties from a fresh download."
    exit 1
fi
if [ ! -f tools/matrix/build.gradle ]; then
    echo "[ERROR] tools/matrix/build.gradle is missing. Re-extract the archive."
    exit 1
fi
if [ ! -f tools/fetch-gradle.sh ]; then
    echo "[ERROR] tools/fetch-gradle.sh is missing. Re-extract the archive."
    exit 1
fi

# ---------------------------------------------------------------- java check
if ! command -v java >/dev/null 2>&1; then
    echo ""
    echo "[ERROR] Java was not found on PATH."
    echo "        Install JDK 17 or newer, e.g. https://adoptium.net/"
    echo "        then run this script again."
    echo ""
    exit 1
fi

# ---------------------------------------------------------------- cleanup mode
# Handled before bootstrapping: --stop is pure cleanup, so downloading 130 MB
# of Gradle just to stop daemons would be absurd.
if [ "$STOP_ONLY" = "1" ]; then
    echo ""
    echo "[gradle] stopping every Gradle daemon found in gradle-dist/ ..."
    stopDaemons all
    DO_STOP=0
    echo "[gradle] done."
    exit 0
fi

# ---------------------------------------------------------------- bootstrap gradle
if ! ensureGradle "$BOOT_GRADLE"; then
    exit 1
fi
BOOT_BIN="$CUR_GRADLE_BIN"
echo "[gradle] bootstrap: $BOOT_BIN"

# A running daemon does not pick up changes in gradle.properties (proxy
# settings are JVM system properties), so stop it first.
echo "[gradle] stopping stale daemons so the proxy settings take effect ..."
"$BOOT_BIN" --stop >/dev/null 2>&1

# ---------------------------------------------------------------- init script
INIT_ARG=""
if [ "$USE_MIRRORS" = "1" ] && [ -f "$PWD/tools/mirrors.gradle" ]; then
    INIT_ARG="-I $PWD/tools/mirrors.gradle"
fi

# ---------------------------------------------------------------- update versions
if [ "$DO_UPDATE" = "1" ]; then
    echo "[matrix] refreshing toolchain versions ..."
    if command -v python3 >/dev/null 2>&1; then
        python3 tools/update_versions.py
    elif command -v python >/dev/null 2>&1; then
        python tools/update_versions.py
    else
        echo "[matrix] python not found, skipped"
    fi
fi

# ---------------------------------------------------------------- wrapper task
if [ "$DO_WRAPPER" = "1" ]; then
    echo "[gradle] generating the standard wrapper ..."
    # shellcheck disable=SC2086
    if ! $BOOT_BIN wrapper --gradle-version "$BOOT_GRADLE" --distribution-type bin --console=plain $INIT_ARG; then
        exit 1
    fi
    echo "[gradle] done. You can now use ./gradlew directly."
    DO_STOP=1
    exit 0
fi

# ---------------------------------------------------------------- read the matrix
# Parsed by a tiny plugin-free Gradle build (tools/matrix) so LF / CRLF / stray
# CR line endings all behave the same. Output rows: mc|loader|gradleVersion|ok
MATRIX_TXT="$(mktemp "${TMPDIR:-/tmp}/spd-matrix-XXXXXX.txt")"
MATRIX_ARGS=()
[ -n "$FILTER_MC" ]     && MATRIX_ARGS+=("-PmcFilter=$FILTER_MC")
[ -n "$FILTER_LOADER" ] && MATRIX_ARGS+=("-PloaderFilter=$FILTER_LOADER")

echo "[matrix] reading matrix.properties ..."
# shellcheck disable=SC2086
if ! "$BOOT_BIN" -p tools/matrix -q printMatrix --console=plain "${MATRIX_ARGS[@]}" > "$MATRIX_TXT"; then
    echo "[ERROR] could not read the version matrix. See the message above."
    rm -f "$MATRIX_TXT"
    exit 1
fi
if [ ! -s "$MATRIX_TXT" ]; then
    echo "[ERROR] the matrix parser produced no output."
    rm -f "$MATRIX_TXT"
    exit 1
fi

# Strip CR with parameter expansion rather than "sed -i": the -i flag behaves
# differently between GNU and BSD (macOS) sed. A trailing CR would otherwise
# end up glued to the last field and corrupt the loader name.
ROWS=()
while IFS='|' read -r mc loader gv ok; do
    mc="${mc%$'\r'}"
    loader="${loader%$'\r'}"
    gv="${gv%$'\r'}"
    ok="${ok%$'\r'}"
    [ -n "${mc:-}" ] || continue
    ROWS+=("$mc|$loader|$gv|${ok:-ok}")
done < "$MATRIX_TXT"

TOTAL=${#ROWS[@]}
if [ "$TOTAL" -eq 0 ]; then
    echo "[ERROR] the matrix is empty - nothing to build."
    echo "        Check mc.versions and the <version>.loaders lines in matrix.properties"
    rm -f "$MATRIX_TXT"
    exit 1
fi

echo "[matrix] $TOTAL combination(s) to build:"
for row in "${ROWS[@]}"; do
    IFS='|' read -r mc loader gv _ <<< "$row"
    echo "           $mc / $loader   (Gradle $gv)"
done

if [ "$DO_LIST" = "1" ]; then
    rm -f "$MATRIX_TXT"
    DO_STOP=1
    exit 0
fi

# ---------------------------------------------------------------- build loop
mkdir -p build/artifacts

BUILT=0
FAILED=""
INDEX=0

for row in "${ROWS[@]}"; do
    IFS='|' read -r mc loader gv _ <<< "$row"
    INDEX=$((INDEX + 1))
    echo ""
    echo "==> [$INDEX/$TOTAL] Building $mc / $loader with Gradle $gv"

    # Remove last run's artifact first: if this build fails we must not be
    # left with an old jar that looks like a fresh one.
    shopt -s nullglob
    stale=(build/artifacts/sharedplayerdata-$mc-$loader-*.jar)
    shopt -u nullglob
    if [ ${#stale[@]} -gt 0 ]; then
        rm -f "${stale[@]}"
        echo "    removed stale artifact from build/artifacts/"
    fi

    if ! ensureGradle "$gv"; then
        echo "    [FAILED] $mc / $loader - could not obtain Gradle $gv"
        FAILED="$FAILED $mc/$loader"
        continue
    fi

    # shellcheck disable=SC2086
    if ! "$CUR_GRADLE_BIN" ":$loader:build" -PmcVersion="$mc" -PtargetLoader="$loader" \
            --console=plain $INIT_ARG $EXTRA_ARGS; then
        echo "    [FAILED] $mc / $loader (continuing with the next one)"
        FAILED="$FAILED $mc/$loader"
        continue
    fi

    shopt -s nullglob
    jars=("$loader"/build/libs/sharedplayerdata-$mc-$loader-*.jar)
    shopt -u nullglob

    GOTJAR=0
    for j in "${jars[@]}"; do
        base="$(basename "$j")"
        # Loom also emits unobfuscated -dev.jar and -sources.jar; skip those.
        case "$base" in
            *-sources.jar|*-dev.jar) continue ;;
        esac
        cp -f "$j" build/artifacts/
        echo "    artifact: build/artifacts/$base"
        GOTJAR=1
    done

    if [ "$GOTJAR" -eq 0 ]; then
        echo "    [WARN] build reported success but no jar was found"
        echo "           expected: $loader/build/libs/sharedplayerdata-$mc-$loader-*.jar"
    else
        for j in "${jars[@]}"; do
            base="$(basename "$j")"
            case "$base" in
                *-sources.jar|*-dev.jar) continue ;;
            esac
            verifyArtifact "$j" "$loader"
        done
    fi
    BUILT=$((BUILT + 1))
done

rm -f "$MATRIX_TXT"

echo ""
echo "==========================================================================="
echo "Build finished. $BUILT of $TOTAL combination(s) succeeded."
if [ -n "$FAILED" ]; then
    echo "Failed:$FAILED"
    log_fail
    exit 1
fi
if [ "$BUILT" -eq 0 ]; then
    echo "[ERROR] not a single combination was built, yet no failure was recorded."
    echo "        This should not happen - please report it with the full log above."
    exit 1
fi
echo "Artifacts are in build/artifacts/"
exit 0
