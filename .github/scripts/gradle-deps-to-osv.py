#!/usr/bin/env python3
"""把 `gradle dependencies` 输出转成 OSV-Scanner 自定义清单(osv-scanner.json)。

背景：OSV-Scanner 的 Java 生态只认 gradle.lockfile / buildscript-gradle.lockfile /
pom.xml 等清单文件，本工程未启用 Gradle 依赖锁定(无 lockfile)，
直接 scan source 会漏掉绝大多数依赖。故先由 Gradle 解析出真实依赖树
(含传递依赖与版本仲裁结果)，再转成 OSV 支持的自定义清单格式。

用法：
  python3 .github/scripts/gradle-deps-to-osv.py <deps.txt> <osv-scanner.json>
"""

import json
import re
import sys

# 匹配依赖行里的 group:artifact:version。
# `gradle dependencies` 用 +--- / \--- 画树，行尾可能带：
#   (n) 未解析 / (c) 约束 / (*) 已在别处展开 / -> 1.2.3 版本仲裁 / FAILED 解析失败
COORD_RE = re.compile(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-+]+)")

# 行尾标注为依赖声明而非实际解析结果，或解析失败者不纳入
SKIP_MARKERS = ("(n)", "FAILED")


def parse(text: str) -> dict[str, str]:
    """返回 {group:artifact: version}，后出现的版本覆盖前者(即 Gradle 仲裁后的胜出版本)。"""
    resolved: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith(("+---", "\\---", "|")):
            continue
        if any(marker in stripped for marker in SKIP_MARKERS):
            continue

        match = COORD_RE.search(stripped)
        if not match:
            continue
        group, artifact, version = match.groups()

        # 版本仲裁：group:art:1.0 -> 2.0 取箭头后的版本
        arrow = re.search(r"->\s*([A-Za-z0-9_.\-+]+)", stripped)
        if arrow:
            version = arrow.group(1)

        # Gradle 用 project(...) 表示工程内依赖，坐标行里不会出现，这里天然跳过
        resolved[f"{group}:{artifact}"] = version
    return resolved


def main() -> int:
    if len(sys.argv) != 3:
        print("用法：gradle-deps-to-osv.py <deps.txt> <osv-scanner.json>", file=sys.stderr)
        return 2

    src, dest = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8", errors="replace") as fp:
        resolved = parse(fp.read())

    if not resolved:
        print(f"[警告] 未从 {src} 解析出任何依赖", file=sys.stderr)

    packages = [
        {
            "package": {
                "name": name,
                "version": version,
                "ecosystem": "Maven",
            }
        }
        for name, version in sorted(resolved.items())
    ]
    payload = {"results": [{"packages": packages}]}

    with open(dest, "w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2)

    print(f"已导出依赖 {len(packages)} 个 -> {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
