#!/usr/bin/env python3
"""Java 8 字节码门禁。

校验核心模块编译产物与发行 jar 内所有 .class 的 major version <= 52(Java 8)。
README 对外承诺 "Java 8+"，一旦有人误调 toolchain 或引入高版本依赖，
产物会在 Java 8 服务器上以 UnsupportedClassVersionError 启动失败，故设为独立门禁。

用法：python3 .github/scripts/check-bytecode.py [仓库根目录]
退出码：0 全部合规；1 存在违规或受检目标缺失。
"""

import struct
import sys
import zipfile
from pathlib import Path

# Java 8 对应的 class 文件 major version 上限
MAX_MAJOR = 52

# 受检模块。build-logic 是 Gradle 插件工程(included build)，编译目标固定 Java 21
# (major 65) 且只运行于构建期、不随发行包分发，必须排除，否则门禁必然失败。
MODULES = [
    "project/core",
    "api",
    "plugin",
    "platform/platform-bukkit",
    "platform/platform-bungee",
    "platform/platform-velocity",
    "diagnostics/diagnostics-arthas",
]

# 发行 jar(shadow 合并后的二合一 jar)路径通配
JAR_GLOB = "plugin/build/libs/ServerProbe-*.jar"

# 发行 jar 内的豁免项。这些条目来自随 jar 内置的三方依赖(sqlite-jdbc 等)，
# Java 8 JVM 不会加载它们，因此不构成"探针要求 Java 9+"：
# - META-INF/versions/** : multi-release jar 的高版本变体目录
# - module-info.class    : 模块描述符，不是运行时加载的类
JAR_EXEMPT_PREFIXES = ("META-INF/versions/",)
JAR_EXEMPT_SUFFIXES = ("module-info.class",)


def major_of(header: bytes) -> int:
    """取 class 文件头第 7~8 字节(big-endian)的 major version。"""
    return struct.unpack(">H", header[6:8])[0]


def collect_module_classes(root: Path, module: str) -> list[tuple[str, int]]:
    """收集模块 build/classes 下 main 源集的 .class，返回 [(展示路径, major)]。"""
    base = root / module / "build" / "classes"
    if not base.is_dir():
        return []
    found = []
    for path in base.rglob("*.class"):
        # 只看 main 源集：test 源集允许用更高版本 JDK 编译
        if "main" not in path.relative_to(base).parts:
            continue
        with path.open("rb") as fp:
            major = major_of(fp.read(8))
        found.append((path.relative_to(root).as_posix(), major))
    return found


def collect_jar_classes(root: Path, jar: Path) -> list[tuple[str, int]]:
    """收集发行 jar 内的 .class(豁免项除外)，返回 [(展示路径, major)]。"""
    found = []
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if not name.endswith(".class"):
                continue
            if name.startswith(JAR_EXEMPT_PREFIXES) or name.endswith(JAR_EXEMPT_SUFFIXES):
                continue
            found.append((f"{jar.relative_to(root).as_posix()}!{name}", major_of(zf.read(name)[:8])))
    return found


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()

    entries: list[tuple[str, int]] = []
    # 目标缺失必须报错：否则模块改名/构建失败会让门禁静默变绿、失去意义
    missing: list[str] = []

    for module in MODULES:
        found = collect_module_classes(root, module)
        if not found:
            missing.append(f"{module}/build/classes/**/main/**")
        entries.extend(found)

    jars = sorted(root.glob(JAR_GLOB))
    if not jars:
        missing.append(JAR_GLOB)
    for jar in jars:
        entries.extend(collect_jar_classes(root, jar))

    violations = [(path, major) for path, major in entries if major > MAX_MAJOR]
    distribution: dict[int, int] = {}
    for _, major in entries:
        distribution[major] = distribution.get(major, 0) + 1

    print(f"已扫描 class 文件 {len(entries)} 个")
    for major in sorted(distribution):
        print(f"  major {major}: {distribution[major]} 个")
    if jars:
        print("发行 jar: " + ", ".join(jar.relative_to(root).as_posix() for jar in jars))
    else:
        print(f"发行 jar: 未匹配到 {JAR_GLOB}")

    for target in missing:
        print(f"[缺失] 未找到受检产物: {target}", file=sys.stderr)

    if violations:
        print(f"[失败] {len(violations)} 个 class 的 major version > {MAX_MAJOR}(Java 8):", file=sys.stderr)
        for path, major in violations:
            print(f"  major {major}: {path}", file=sys.stderr)
        return 1

    if missing:
        print("[失败] 受检产物缺失，门禁无法确认", file=sys.stderr)
        return 1

    print(f"[通过] 全部 class 的 major version <= {MAX_MAJOR}(Java 8)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
