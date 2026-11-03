#!/usr/bin/env python3
"""把 matrix.properties 里的工具链版本号更新为 Maven 上的最新版。

    python3 tools/update_versions.py           # 直接改写 matrix.properties
    python3 tools/update_versions.py --check   # 只打印将要发生的改动

只改它能确认的版本号；取不到就原样保留并给出提示（不会让构建脚本跑不起来）。

网络：与 gradle.properties 保持一致，默认走 127.0.0.1:10808 代理。
可用环境变量 SPD_PROXY 覆盖，例如：
    SPD_PROXY=socks5://127.0.0.1:10808 python3 tools/update_versions.py
    SPD_PROXY= python3 tools/update_versions.py      # 置空 = 不走代理
"""
from __future__ import annotations

import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "matrix.properties"
TIMEOUT = 25

# 与 gradle.properties / build.bat 里的代理设置保持一致
PROXY = os.environ.get("SPD_PROXY", "http://127.0.0.1:10808")

FORGE_META = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
NEOFORGE_META = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
FABRIC_LOADER_META = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml"
LOOM_META = "https://maven.fabricmc.net/net/fabricmc/fabric-loom/fabric-loom.gradle.plugin/maven-metadata.xml"
FORGEGRADLE_META = "https://maven.minecraftforge.net/net/minecraftforge/gradle/ForgeGradle/maven-metadata.xml"
NEOGRADLE_META = "https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml"


def build_opener():
    """构造走代理的 opener。代理地址非法时回落到直连，不至于让整个脚本挂掉。"""
    handlers = []
    if PROXY:
        try:
            handlers.append(urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))
        except Exception as exc:
            print(f"[warn] 代理 {PROXY} 配置失败（{exc}），改为直连")
    return urllib.request.build_opener(*handlers)


OPENER = build_opener()


def fetch_versions(url: str) -> list[str]:
    req = urllib.request.Request(url, headers={"User-Agent": "sharedplayerdata/1.0"})
    with OPENER.open(req, timeout=TIMEOUT) as resp:
        data = resp.read()
    root = ET.fromstring(data)
    return [e.text.strip() for e in root.iter("version") if e.text and e.text.strip()]


def key(v: str) -> tuple:
    numeric = re.split(r"[^0-9]+", v)
    return tuple(int(p) for p in numeric if p.isdigit())


def pick(versions: list[str], prefix: str = "", prefer_stable: bool = True) -> str | None:
    candidates = [v for v in versions if v.startswith(prefix)] if prefix else list(versions)
    candidates = [v for v in candidates if v[len(prefix):].replace(".", "").isdigit() or not prefer_stable]
    if not candidates:
        return None
    stable = [v for v in candidates if "-" not in v[len(prefix):]]
    pool = stable if (prefer_stable and stable) else candidates
    return max(pool, key=key)


def load_matrix() -> list[str]:
    return MATRIX.read_text(encoding="utf-8").splitlines()


def get_prop(lines: list[str], name: str) -> str | None:
    for line in lines:
        m = re.match(rf"\s*{re.escape(name)}\s*=\s*(.*)$", line)
        if m:
            return m.group(1).strip()
    return None


def set_prop(lines: list[str], name: str, value: str) -> bool:
    """就地替换；找不到该键就返回 False。"""
    for i, line in enumerate(lines):
        if re.match(rf"\s*{re.escape(name)}\s*=", line):
            if line.strip().startswith("#"):
                continue
            indent = line[: len(line) - len(line.lstrip())]
            lines[i] = f"{indent}{name}={value}"
            return True
    return False


def main() -> int:
    check_only = "--check" in sys.argv
    lines = load_matrix()
    mc_versions = (get_prop(lines, "mc.versions") or "").split()
    if not mc_versions:
        print("matrix.properties 里没有 mc.versions")
        return 1

    changes: list[tuple[str, str, str]] = []

    def update(name: str, value: str | None) -> None:
        if not value:
            return
        old = get_prop(lines, name)
        if old is None:
            return
        if old == value:
            return
        if set_prop(lines, name, value):
            changes.append((name, old, value))

    caches: dict[str, list[str]] = {}

    def versions_of(url: str) -> list[str]:
        if url not in caches:
            try:
                caches[url] = fetch_versions(url)
            except Exception as exc:  # 网络不通 / 地址变动
                print(f"  ! 无法获取 {url}（{exc}）")
                caches[url] = []
        return caches[url]

    for mc in mc_versions:
        loaders = (get_prop(lines, f"{mc}.loaders") or "").split()
        print(f"[{mc}] loaders = {loaders or '-'}")

        if "forge" in loaders:
            forge_versions = versions_of(FORGE_META)
            latest = pick(forge_versions, prefix=f"{mc}-")
            update(f"{mc}.forge", latest.split("-", 1)[1] if latest else None)
            fg = pick(versions_of(FORGEGRADLE_META))
            update(f"{mc}.forgeGradle", fg)

        if "neoforge" in loaders:
            neo_versions = versions_of(NEOFORGE_META)
            prefix = mc[2:] + "." if mc.startswith("1.") else mc + "."  # 1.21.1 -> 21.1.
            latest = pick(neo_versions, prefix=prefix, prefer_stable=True)
            update(f"{mc}.neoforge", latest)
            ng = pick(versions_of(NEOGRADLE_META))
            update(f"{mc}.neoGradle", ng)

        if "fabric" in loaders:
            update(f"{mc}.fabricLoader", pick(versions_of(FABRIC_LOADER_META)))
            update(f"{mc}.loom", pick(versions_of(LOOM_META)))

    if not changes:
        print("\n没有需要更新的版本号。")
        return 0

    print("\n将要更新：")
    for name, old, new in changes:
        print(f"  {name}: {old} -> {new}")

    if check_only:
        print("\n（--check 模式，未写入文件）")
        return 0

    MATRIX.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n已写入 {MATRIX}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
