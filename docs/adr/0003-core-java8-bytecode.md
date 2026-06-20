# ADR-3：核心 Java 8 字节码，胶水按需抬 toolchain

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

ServerProbe 需覆盖 MC 1.8–1.21.11 全版本，对应 JRE 从 Java 8 到 21+ 不等，产物必须可被所有这些 JRE 加载。JDK8 无法读取 major≥53 的 class，因此若核心用高版本字节码编译，低版本 JRE 将直接 `UnsupportedClassVersionError`。同时，"直接 `extends`/typed-reference 高版本 NMS 类"会因 javac 编译子类必须加载验证父类 class 而被迫抬高 toolchain（"传染"），而通过反射访问（`nmsClass`/`getProperty`）可避免被未直接引用的高版本类型传染。

## 决策
核心模块统一编译为 Java 8 字节码，仅极少数必须直接引用高版本类型的胶水按需抬高 toolchain。

## 理由
- Java 8 字节码保证产物可被 1.8–1.21.x 全部 JRE 加载，满足全版本兼容这一硬约束。
- `compileOnly` 高字节码依赖本身不强制抬 toolchain（javac 不加载未被直接引用的 class）；真正"传染"的是直接出现在源码符号里的高版本类型。
- 因此优先反射访问可避免 toolchain 传染；把"必须直接引用高版本类型"的代码隔离到独立 `nms-vXXX` 模块单独抬 toolchain，绝不与 Java 8 核心混编（见 ARCHITECTURE.md §6）。

## 后果
- 正面：单一产物全 JRE 通用；按需加载保证低版本 JVM 永不触碰高版本 class，不会 `UnsupportedClassVersionError`。
- 负面/约束：需坚持"优先反射"，把直接引用高版本类型的代码隔离到独立模块；跨 toolchain 依赖只能单向——高版本模块可 `compileOnly` Java 8 模块，Java 8 核心绝不反向依赖高版本模块产物，只通过接口 + 运行时反射装配。

## 备选方案
- **全 Java 17（放弃低版本）**：会放弃 1.8–1.16 等低版本服务端的支持，与全版本兼容目标冲突。否决。
