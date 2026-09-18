#!/usr/bin/env bash
# =============================================================================
#  Download and unpack Gradle into <project>/gradle-dist/gradle-<Version>.
#
#      bash tools/fetch-gradle.sh -Version 8.10
#      bash tools/fetch-gradle.sh -Version 8.10 -NoProxy
#      bash tools/fetch-gradle.sh -Version 8.10 -Proxy http://127.0.0.1:7890
#
#  Called by build.sh; safe to run directly as well.
#  Exits 0 if Gradle is already present or was unpacked successfully.
#
#  Proxy defaults to http://127.0.0.1:10808, the same port as gradle.properties.
#  Pass -NoProxy for a direct download (used by CI).
#
#  Mirrors are tried in order: Tencent -> Aliyun -> Huawei -> official.
# =============================================================================
set -uo pipefail

VERSION="8.10"
PROXY="${SPD_PROXY:-http://127.0.0.1:10808}"
NO_PROXY=0

while [ $# -gt 0 ]; do
    case "$1" in
        -Version)  VERSION="${2:-}"; shift 2 ;;
        -Proxy)    PROXY="${2:-}";   shift 2 ;;
        -NoProxy)  NO_PROXY=1;       shift ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

[ "$NO_PROXY" = "1" ] && PROXY=""

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$ROOT_DIR/gradle-dist"
GRADLE_HOME="$DIST_DIR/gradle-$VERSION"

# Already there? Nothing to do.
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "[gradle] Gradle $VERSION already present at $GRADLE_HOME"
    exit 0
fi

# ---------------------------------------------------------------- downloader
# curl is preferred (handles -x proxy cleanly); fall back to wget.
DOWNLOADER=""
if command -v curl >/dev/null 2>&1; then
    DOWNLOADER="curl"
elif command -v wget >/dev/null 2>&1; then
    DOWNLOADER="wget"
else
    echo "[gradle] ERROR: neither curl nor wget is installed."
    echo "         Install one of them, then run build.sh again."
    exit 1
fi

# fetch <url> <outfile>
fetch() {
    local url="$1" out="$2"
    if [ "$DOWNLOADER" = "curl" ]; then
        if [ -n "$PROXY" ]; then
            curl -fL --progress-bar --max-time 900 -x "$PROXY" -o "$out" "$url"
        else
            curl -fL --progress-bar --max-time 900 -o "$out" "$url"
        fi
    else
        if [ -n "$PROXY" ]; then
            http_proxy="$PROXY" https_proxy="$PROXY" \
                wget -q --timeout=900 -O "$out" "$url"
        else
            wget -q --timeout=900 -O "$out" "$url"
        fi
    fi
}

MIRRORS=(
    "https://mirrors.cloud.tencent.com/gradle/gradle-$VERSION-bin.zip"
    "https://mirrors.aliyun.com/gradle/gradle-$VERSION-bin.zip"
    "https://mirrors.huaweicloud.com/gradle/gradle-$VERSION-bin.zip"
    "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
)

mkdir -p "$DIST_DIR"
ZIP_FILE="$DIST_DIR/gradle-$VERSION-bin.zip"

echo "[gradle] downloading Gradle $VERSION (about 130 MB, one-time) ..."
[ -n "$PROXY" ] && echo "[gradle] using proxy: $PROXY"

GOT=""
for url in "${MIRRORS[@]}"; do
    echo "          try ${url%%/gradle*}"
    rm -f "$ZIP_FILE"
    if fetch "$url" "$ZIP_FILE"; then
        # Guard against a 0-byte or truncated file masquerading as success.
        if [ -s "$ZIP_FILE" ] && [ "$(wc -c < "$ZIP_FILE" | tr -d ' ')" -gt 1000000 ]; then
            echo "          downloaded $(( $(wc -c < "$ZIP_FILE" | tr -d ' ') / 1048576 )) MB"
            GOT="$url"
            break
        fi
        echo "          too small, treating as a failed download"
    fi
done

if [ -z "$GOT" ]; then
    echo ""
    echo "[gradle] ERROR: could not download Gradle $VERSION from any mirror."
    echo "         Options:"
    echo "           * set a proxy:  SPD_PROXY=http://127.0.0.1:7890 ./build.sh"
    echo "           * download it by hand from https://services.gradle.org/distributions/"
    echo "             and unpack it so that gradle-dist/gradle-$VERSION/bin/gradle exists"
    exit 1
fi

# ---------------------------------------------------------------- unzip
echo "[gradle] unzipping ..."
if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP_FILE" -d "$DIST_DIR"
    RC=$?
else
    echo "[gradle] unzip not found - trying python3 zipfile ..."
    python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
        "$ZIP_FILE" "$DIST_DIR"
    RC=$?
fi

if [ $RC -ne 0 ] || [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "[gradle] ERROR: unpacking failed."
    rm -f "$ZIP_FILE"
    exit 1
fi

chmod +x "$GRADLE_HOME/bin/gradle" 2>/dev/null
rm -f "$ZIP_FILE"

echo "[gradle] ready: $GRADLE_HOME/bin/gradle"
exit 0
