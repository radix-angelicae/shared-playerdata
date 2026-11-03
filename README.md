# Shared Player Data

![Build](https://github.com/radix-angelicae/shared-playerdata/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/badge/license-MIT-green)

解决 Minecraft Java 版那个经典坑：**房主把存档发给别人后，接手者打开世界会变成房主的背包、位置、经验和进度**，
甚至会把自己原本的角色数据覆盖掉。

一个纯客户端模组，不装服务端也能用；Forge / Fabric / NeoForge 三加载器、1.19.x ~ 1.21.x 多版本一套代码通吃。

---

## 一、问题根源（为什么要做这个）

Java 版 1.21.11 及更早版本，单人世界会把「世界所有者」的完整玩家数据**额外**存一份到
`level.dat → Data → Player`。而单人模式打开世界时，游戏**无条件优先读这份数据，完全不看当前玩家的 UUID**。
这就是 [Mojira MC-697](https://bugs.mojang.com/browse/MC-697) 描述的行为：

* 房主开「对局域网开放」= 先单人进世界再发布，所以 `level.dat` 里那份就是房主的；
* 其他人联机时，各自的数据本来是分开存在 `playerdata/<UUID>.dat` 的；
* 一旦有人把存档拿去**单人打开**，游戏就强行读房主那份，还会把接手者自己的 `.dat` 覆盖掉。

Minecraft **26.1** 起官方改了机制：`level.dat` 里的 `Player` 复合标签换成 `singleplayer_uuid` 指针，
真正的数据统一放 `players/data/<UUID>.dat`，单人存档也按 UUID 读了。所以在 26.1+ 上这个问题已经不存在，
本模组对 26.1 的新格式同样做了兼容处理（误指向他人时一并清掉）。

**本模组的做法**：在玩家打开任何世界之前，扫描 saves 目录。若发现某个 `level.dat` 里保存的是**别人**的玩家数据，
就把它摘掉。此时游戏会退回按 UUID 读取 `playerdata/<你的UUID>.dat`（没有就以新手身份在出生点重生），各拿各的。
摘除前还会把自己那份数据备份成 `<UUID>.dat.spd_backup`，万一此前已经被污染也有救。

设计原则：**零配置、零命令、只做自动修正**；核心逻辑不依赖任何 Minecraft 类和加载器 API（自带 NBT 读写），
所以三个加载器共用同一份核心代码，跨版本几乎不会碎。

---

## 二、游戏内命令 `/spd`

| 命令 | 作用 |
|---|---|
| `/spd status` | 显示你的 UUID、每个存档的内嵌数据归属、你有没有自己的数据 |
| `/spd list` | 列出各存档 `playerdata/` 里现存的每份数据（UUID + 大小 + 时间） |

两个都是**只读**的诊断命令，在世界中随时可用。命令输出同时写进日志
（`logs/latest.log`）。`/spd` 是客户端命令，**不需要开作弊**。

> Fabric 上命令依赖 Fabric API，没装就不会注册（存档修正仍然正常工作）。
> 注册失败会在日志里写明原因，不会导致崩溃。

### 为什么没有写盘类命令

修正工作**全部自动完成**：在主菜单（不在世界中）时扫描所有存档、清掉属于别人的
内嵌数据并归档，之后游戏按 UUID 各读各的，玩家不需要做任何事。运行中导入的新
存档，回到主菜单也会自动扫。

曾经提供过 `/spd scan`、`/spd adopt`、`/spd reload`，现已移除——它们带来的
「与游戏抢文件、写坏存档」的风险，大于其带来的便利。真需要手工认领数据时，
直接用文件管理器操作即可（见「UUID 对不上」一节的说明）。

## 三、进存档后背包是空的？

先跑一次 `/spd status`，它会直接告诉你真相：

```
我的 UUID: xxxxxxxx-xxxx-...
· 我的世界
    内嵌数据: yyyyyyyy-...（别人的）
    我的数据: ★ 没有（进去会是空号）
    数据文件: 2 份
      - yyyyyyyy-... (他人)
      - zzzzzzzz-... (他人，空号)
```

**重点看「我的 UUID」有没有出现在下面的数据文件列表里。** 没有就是空号的原因。

> ### 一个 Minecraft 的固有行为（不是模组 bug）
>
> 单人世界里，**房主的数据权威副本存在 `level.dat` 的 `Data/Player`**，
> 而 `playerdata/<房主UUID>.dat` 可能不存在、或者只是一份空壳。
> 所以把单人存档直接拿去开服务器，**房主自己进去也会是空号**——原版就这样。
>
> 本模组会把 `Data/Player` 归档成 `playerdata/<房主UUID>.dat` 来补上这一课。
> 如果那份 playerdata 已经存在但是空号，模组会**备份后用内嵌数据覆盖**。

## 三点五、UUID 为什么会「看起来没变」却对不上

装了模组之后进老存档变成“新号”，通常不是模组把数据删了，而是**你的 UUID 和存档里那份数据对不上**：

- 正版账号和离线账号的 UUID 算法不同（离线是 `MD5("OfflinePlayer:"+名字)`）；
- 联机时用的是服务器分配的 UUID，单人打开时用当前登录账号的 UUID；
- 启动器不同（PCL2 / HMCL / 官方）算出来的离线 UUID 也可能不一致。

常见但容易忽略的几种：

- **验证模式变了**：联机时进的是离线服（UUID = `MD5("OfflinePlayer:"+名字)`），
  单人打开时用的是正版账号（UUID 来自 Mojang）——同一个玩家，两个 UUID；
- **游戏名大小写**：离线 UUID 对名字大小写敏感；
- **启动器不同**：PCL2 / HMCL / 官方启动器算离线 UUID 的方式不完全一致。

按顺序排查：

1. `/spd status` —— 看「我的数据」是不是「★ 没有」，并记下你的 UUID
2. `/spd list` —— 看这个世界里实际存了哪些 UUID
3. 退出到主菜单，让模组自动扫描一次（主菜单时会扫）
4. 如果确认某份数据属于你但 UUID 对不上，手工处理：
   把 `playerdata/<那份UUID>.dat` 复制一份，改名为 `<你的UUID>.dat`。
   **改之前先备份**原来的 `<你的UUID>.dat`（如果有）。
5. 重进世界即可。

## 四、已有存档如何接入本模组

第一次装上本模组、打开一个老存档时，模组会把 `level.dat` 里内嵌的那份玩家数据
**归档**成独立的 `playerdata/<原主人的UUID>.dat`，再清掉内嵌标签。所以：

- **数据不会丢**——房主自己打开这个世界时，会正常拿回自己的东西；
- 其他人打开时，各自读各自的 `playerdata/<自己UUID>.dat`；
- 谁在这个世界里都没有数据、且内嵌数据的 UUID 无法辨认时，模组**保留**内嵌数据，
  不会把存档变成空号。

模组还会在后台常驻：只要你在主菜单（不在世界里），每 15 秒重新扫描一次，
所以新导入的存档不必重启游戏。

## 五、目录结构

```
shared-playerdata/
├── .github/workflows/build.yml  ← GitHub Actions：手动触发，只跑已验证组合
├── build.bat              ← 一键构建（Windows）
├── build.sh               ← 一键构建（Linux / macOS）
├── push.bat               ← 一键提交 + 推送到 GitHub（Windows）
├── matrix.properties      ← 版本矩阵：全部 MC 版本 / 加载器 / 工具链版本号（唯一来源）
├── settings.gradle        ← 根据当前 MC 版本决定 Loom / ForgeGradle / NeoGradle 插件版本
├── build.gradle
├── core/                  ← 核心逻辑（纯 Java，不依赖 MC）
│   └── src/main/java/org/sharedplayerdata/core/
│       ├── Tag.java          极简 NBT 标签
│       ├── NbtIo.java        NBT 读写（自带 GZIP 处理）
│       ├── Uuids.java        UUID ↔ int[4]
│       ├── LevelDatFixer.java 核心修正逻辑（含内嵌数据归档）
│       ├── PlayerDataStore.java   玩家数据文件的列出 / 定位
│       ├── SpdRuntime.java    运行期状态：扫描 / 状态
│       ├── CommandBridge.java /spd 命令的实现（反射搭 Brigadier 语法树）
│       ├── CommandHook.java   把命令挂到三个加载器各自的注册点
│       ├── SharedPlayerData.java  对外门面
│       ├── SpdLog.java       日志出口
│       ├── Main.java         命令行入口（不装模组也能修存档）
│       └── SelfTest.java     零依赖自测
├── mcbridge/              ← 与 Minecraft 打交道的桥接实现（三个加载器共用源码）
│   └── src/main/java/org/sharedplayerdata/mcbridge/
│       ├── PlatformImpl.java   SpdPlatform 的编译期实现
│       └── ClientScanner.java  等待客户端就绪 → 扫描 → 挂命令 → 启动看门狗
├── fabric/                ← Fabric 桥接层（进客户端即扫描）
├── forge/                 ← Forge 桥接层（mod 加载完成后扫描）
├── neoforge/              ← NeoForge 桥接层
├── tools/
│   ├── update_versions.py ← 从 Maven 拉最新工具链版本号，改写 matrix.properties
│   ├── jcheck.py          ← 静态体检（final 赋值 / 括号 / 漏定义方法 / 漏 import），不需 JDK
│   ├── fetch-gradle.ps1   ← 下载 Gradle（Windows，供 build.bat 调用）
│   ├── fetch-gradle.sh    ← 下载 Gradle（Linux / macOS，供 build.sh 调用）
│   ├── inspect-jar.bat    ← 打印 jar 里的元数据，确认 license 等字段
│   ├── selftest.bat       ← 只跑核心逻辑自测（不需要 Gradle / Minecraft）
│   └── selftest.sh        ← 同上，Linux / macOS 版
├── build.bat              ← 一键构建（Windows）
├── build.sh               ← 一键构建（Linux / macOS）
└── build/artifacts/       ← 构建产物（成功一个放一个）
```

`core/` 与 `mcbridge/` 都以**源码形式**并入每个加载器子项目
（`srcDirs += ['../core/src/main/java', '../mcbridge/src/main/java']`），
因此不需要 jar-in-jar、shadow 之类的额外插件，产物就是干净的单个 jar。

两者分工固定，别把代码放错地方：

| 目录 | 能不能碰 Minecraft 类 | 原因 |
|---|---|---|
| `core/` | **不能** | 纯 Java，便于直接 javac 编译自测 |
| `mcbridge/` | 可以（且**必须**用编译期符号） | 混淆重映射只对编译期符号引用生效 |

> **教训**：`core/` 里写 `mc.getClass().getField("level")` 在开发时能跑，
> 到玩家机器上必然 `NoSuchFieldException`——字符串字面量不会被 SRG / intermediary
> 重写。凡是涉及 MC 成员的，一律放 `mcbridge/`。

---

## 六、快速开始

### 1. 环境

只需要 **JDK 17 或更高版本**（[Adoptium](https://adoptium.net/) 下一个即可）。

### 2. 代理（已预配置）

所有下载统一走 `127.0.0.1:10808`，已经写进配置，无需额外操作：

| 环节 | 生效位置 |
|---|---|
| Gradle 依赖解析（Forge / NeoForge / Fabric Maven） | `gradle.properties` 的 `systemProp.*` |
| Fabric Loom 拉 Minecraft 本体、Mojang 映射、资源 | 同上（Loom 走 JVM 的 HTTP 连接） |
| ForgeGradle 的 MCP / 反编译步骤 | 同上 |
| 下载 Gradle 分发包本身 | `build.bat` 顶部的 `PROXY_HOST` / `PROXY_PORT` |
| `update_versions.py` 抓版本号 | 脚本内的 `PROXY` 常量 |

**换端口**：改 `gradle.properties` 里的 4 行 `proxyPort`，以及 `build.bat` 的
`PROXY_PORT`。

**你的 10808 是 SOCKS5 而不是 HTTP** 的话，把 `gradle.properties` 里那几行换成：

```properties
systemProp.socksProxyHost=127.0.0.1
systemProp.socksProxyPort=10808
```

**临时不走代理**：先执行 `set SPD_PROXY=` 再运行 `build.bat`。

> 改完代理配置后，脚本会先执行一次 `gradle --stop` 再构建——正在运行的 daemon
> 不会感知 `gradle.properties` 的改动，不重启的话新设置不生效。

走代理之后访问官方仓库已经足够快，所以默认**不启用**国内镜像（`--mirrors` 可开启）。

* **Gradle 完全不用管**：首次运行会自动下载 Gradle 8.10 到项目内的 `gradle-dist/`（约 130MB，只下一次）。
  已经装了 Gradle 也能直接用，脚本会优先找系统里的。
* 编译 1.19.2 ~ 1.20.4 需要 JDK **17**，1.20.6 / 1.21.x 需要 JDK **21**。
  **建议两个都装**（[Adoptium](https://adoptium.net/)），Gradle 会自己挑合适的那个。

> **关于 Foojay 工具链解析器**：它可以让 Gradle 在缺 JDK 时自动下载，但要求 Gradle 8+，
> 而 1.19.2 的 Forge 组合跑在 Gradle 7.5.1 上，一启用就在配置阶段失败。
> 所以**默认关闭**。需要的话看 `settings.gradle` 里的注释（有两处要同时改）。

### 2. 校准版本号（强烈建议先做一次）

`matrix.properties` 里的 Forge / NeoForge / Loom 版本是我按常见值填的，**可能已经过时**。先跑：

```bash
python3 tools/update_versions.py        # 自动从 Maven 抓最新版本号改写 matrix.properties
python3 tools/update_versions.py --check  # 只看会改什么，不落盘
```

取不到的项会原样保留并提示，不会把文件写坏。

### 3. 一键构建

Windows 直接双击 `build.bat`（或命令行运行）；Linux / macOS 用 `./build.sh`（需先
`chmod +x build.sh`，或用 `bash build.sh`）。两者的参数与流程完全一致：

```bash
build.sh                      # 全部组合
build.sh 1.20.1               # 只构建 1.20.1 的所有加载器
build.sh 1.20.1 fabric        # 只构建 1.20.1 + Fabric
build.sh 1.20.1 1.21.1        # 构建两个版本（各自的所有加载器）
build.sh 1.20.1 1.21.1 fabric # 两个版本，只要 Fabric
build.sh fabric               # 所有版本的 Fabric
```

位置参数按**含义**归类，不按先后顺序：写成 `fabric` / `forge` / `neoforge` 的当加载器，
其余当 MC 版本，而且**版本可以并列多个**。所以上面几种写法都成立，
`1.20.1 1.21.1` 不会把第二个版本号误当成加载器名。
输出顺序按你写的顺序来，不会被 `matrix.properties` 里的顺序重排。

版本名或加载器名写错时会明确列出可选值，而不是含糊地报「没有匹配的组合」：

```
[matrix] ERROR: unknown MC version(s): 1.30.1
[matrix] available versions: 1.19.2 1.19.4 1.20.1 1.20.4 1.20.6 1.21.1 1.21.5 1.21.11
```
build.sh --list               # 只列出将要构建的组合，不构建
build.sh --update-versions    # 先校版本号再构建
build.sh --wrapper            # 生成标准 gradlew
build.sh --no-mirrors         # 不使用国内镜像
build.sh --stacktrace         # 失败时打完整堆栈
build.sh --no-stop            # 构建后保留 Gradle daemon（默认会停掉）
build.sh --stop               # 只停掉所有 Gradle daemon 并退出，不构建
```

> `build.bat` 另有 `--no-pause`（结束时不等按键，CI 用）和 `--no-proxy`。
> `--no-proxy` 两个脚本都支持。`--no-pause` 只在批处理里有意义，`build.sh`
> 也接受它但不做任何事，这样同一条 CI 命令在两个平台上不用改。

先跑一次 `build.sh --list` 是个好习惯：它会打印解析到的组合清单，一眼确认
`matrix.properties` 有没有被正确读到，再决定要不要全量构建。

**构建结束后会自动停掉 Gradle daemon**，后台不会残留 `java` 进程。
（`gradle --stop` 只作用于同版本的 daemon，所以脚本会按本次用到的版本逐个停。
历史遗留的进程可以用 `build.sh --stop` 一次性清掉。）
`build.sh` 用 `trap ... EXIT` 兜底，**中途 Ctrl+C 也会清理**；`build.bat` 无此能力
（Windows 批处理拦不住 Ctrl+C），需要清理时用 `build.bat --stop`。

| | Windows | Linux / macOS |
|---|---|---|
| 构建 | `build.bat` | `./build.sh` |
| Gradle 下载 | `tools/fetch-gradle.ps1` | `tools/fetch-gradle.sh` |
| 核心自测 | `tools\selftest.bat` | `./tools/selftest.sh` |
| 提交 | `push.bat` | 用 git 命令，或参照 `push.bat` |

三个 `.sh` 脚本都要求 **LF 行尾**——CRLF 的 shebang 会让 Linux 报
`bad interpreter`。仓库里的 `.gitattributes` 已用 `*.sh text eol=lf` 钉死，
即使在 Windows 上 `core.autocrlf=true` 也不会被改成 CRLF。

脚本会自动完成：**检查 JDK → 没有 Gradle 就下载 → 逐个组合构建 → 收集产物**。
双击运行时窗口结束前会等你按一下键，方便看结果；命令行调用则直接返回。

单个组合失败不会中断其余构建，最后会汇总打印哪些组合失败。
产物在 `build/artifacts/`，命名 `sharedplayerdata-<mc版本>-<加载器>-<模组版本>.jar`。

> 不想每次构建都停 daemon（比如你要连续调试），加 `--no-stop` 让 daemon 常驻以加快后续
> 构建；代价就是后台会留着 `java.exe`，需要时用 `build.bat --stop` 清理。

> 第一次构建会比较慢：除了下载 Gradle，Loom / ForgeGradle 还要反编译 Minecraft、
> 下载 Forge / Fabric / NeoForge 的依赖，单个版本可能 5~15 分钟，全部 20 个组合一小时上下，属于正常现象。

#### 附带的脚本

| 文件 | 作用 |
|---|---|
| `tools/fetch-gradle.ps1` | 下载并解压 Gradle 到 `gradle-dist/`（多镜像：腾讯 → 阿里 → 华为 → 官方） |
| `tools/inspect-jar.bat` | 打印 mod jar 里的元数据，确认 `license` 等字段是否真的打进了 jar |
| `tools/mirrors.gradle` | 可选加速：把 Maven Central / Gradle 插件门户换成国内镜像（默认**关闭**，`--mirrors` 开启） |
| `tools/matrix/` | 零插件的独立小 Gradle 工程，负责解析 `matrix.properties` 并输出「MC版本\|加载器」清单 |
| `tools/update_versions.py` | 从 Maven 抓最新 Forge / NeoForge / Loom 版本号改写 `matrix.properties` |
| `tools/selftest.bat` | 只跑核心逻辑自测，不需要 Gradle / Minecraft |

如果公司的网络访问官方仓库慢，或者依赖下载超时，试试 `build.bat --no-mirrors` 换回官方源。

### 4. 安装

把对应版本 + 加载器的 jar 丢进 `.minecraft/mods/`（**只装客户端即可**，服务端不需要，也不该装）。
**要求每个可能接到存档的人都要装**——谁没装，谁打开别人存档时就会继续继承房主数据。

### 5. 验证核心逻辑（可选，不用启动 MC）

```bash
tools\selftest.bat
```

会先做一遍静态检查（括号配平 / 调用了没定义的方法 / 用了没 import 的类型），
再编译 core 并跑自测：他人存档被修正、自己的存档不动、备份生成、26.1 新格式处理、
重复扫描幂等、认领复制的是目标文件、残缺数据不会被当空号覆盖。

> 这四类静态错误在本项目的前几个版本里都出现过，都是编译阻断级，
> 所以 `selftest.bat` 会在编译前先拦一道。

---

## 七、增删目标版本

只改 `matrix.properties`：

```properties
mc.versions=1.19.2 1.19.4 1.20.1 1.20.4 1.20.6 1.21.1 1.21.5 1.21.11
1.20.1.loaders=fabric forge
1.20.1.java=17
1.20.1.fabricLoader=0.15.11
1.20.1.loom=1.4.7
1.20.1.forge=47.4.0
1.20.1.forgeGradle=6.0.24
1.20.1.forgeLoaderRange=[47,)
```

* `loaders` 取值：`fabric` / `forge` / `neoforge`（NeoForge 从 1.20.2 起才有，1.20.1 及更早不要写）。
* `java`：1.20.5 之前填 17，之后填 21。
* 想加 1.21.4？复制 `1.21.1.*` 那几行改前缀即可，再把 `1.21.4` 加进 `mc.versions`。

---

## 八、常见问题

**Q：提示 “No matching toolchains found for 21” / “for 17”**
1.19.2 ~ 1.20.4 需要 JDK **17**，1.20.6 / 1.21.x 需要 JDK **21**。
两个都装上最省事（[Adoptium](https://adoptium.net/)），Gradle 会自己挑合适的那个。
只装了一个的话，缺的那个版本构建会失败，装上即可。

**Q：Fabric 报 “Plugin [id: 'fabric-loom', version: 'X'] was not found”**
这个 Loom 版本号在 Maven 上不存在（Gradle 会依次搜 Fabric / Forge / NeoForge / 中央仓库，
全都没有才这么报）。

改 `matrix.properties` 里对应那一行即可：

```properties
1.20.1.loom=1.8.12
```

`1.8.12` 是目前**实测能解析并成功构建**的版本（1.21.1/fabric 已用它产出 jar），
矩阵里所有 Fabric 组合默认都填它。Loom 本身与 MC 版本无关，所以老版本 MC 也能用新 Loom。
想换别的值，去 <https://maven.fabricmc.net/net/fabricmc/fabric-loom/> 看真实存在的版本号。

**Q：Forge 报 “Could not create an instance of type ... DefaultToolchainSpec”**
ForgeGradle 6 依赖 Gradle 内部 API，官方明确说每个版本只保证支持一个 Gradle 大版本。
实测 FG 6.0.30 在 Gradle 8.10 上会炸，所以 `matrix.properties` 里所有 forge 组合
统一指定 `1.x.forge.gradle=8.8`（脚本会自动下载这份 Gradle）。
如果 8.8 还是不行，改成 **8.4**：

```properties
1.21.1.forge.gradle=8.4
```

**Q：游戏启动时报 “Missing license (sharedplayerdata-xxx.jar)”**

`license` 是**非模组特定属性**，必须写在元数据文件的**顶层**——也就是和
`modLoader`、`loaderVersion` 同级，在第一个 `[[mods]]` **之前**。

写进 `[[mods]]` 段里是无效的，FML 只从顶层读，照样抛 `Missing license`：

```toml
modLoader = "javafml"
loaderVersion = "[1,)"
license = "MIT"        # ← 对：在顶层

[[mods]]
modId = "sharedplayerdata"
# license = "MIT"      ← 错：写在 [[mods]] 里不会被识别
```

判断手上的 jar 到底是哪种情况：

```bat
tools\inspect-jar.bat 你的jar路径
```

会打印 jar 里的 `neoforge.mods.toml` 并明确告诉你 `[OK] license is at the top level`、
`[!!] license is INSIDE the [[mods]] section`，还是 `[!!] NO license field`。
确认是新 jar 之后，把 mods 目录里的旧 jar **删掉**再放新的。

**Q：游戏日志里那些 WARN 要不要紧**

| 日志 | 说明 |
|---|---|
| `Mod file ... fmlcore-xxx.jar is missing mods.toml file` | Forge 自己的库，**正常**，每个 Forge 环境都有 |
| `Failed to verify authentication ... Status: 401` | 离线启动 / 正版验证服务器连不上，**与模组无关**。你用的是 `test1` 这类离线账号，必然 401 |
| `Could not retrieve profile key pair` | 同上，离线账号没有密钥对，**正常** |
| `Missing sound for event: minecraft:item.goat_horn.play` | 原版自己的问题，**正常** |
| `Missing metadata in pack mod:sharedplayerdata` | 以前缺 `pack.mcmeta`，现在已补上；重建后消失 |

`pack.mcmeta` 的 `pack_format` 每个 MC 版本都不一样，值写在 `matrix.properties` 的
`<mc>.packFormat` 里。如果某个版本你觉得填错了，改那一行重新构建即可
（填**偏小**是安全的，只会被当作旧版本资源包；填偏大才会报错）。

**Q：构建 forge 时日志里也出现 “Configure project :fabric / Fabric Loom”**
正常的，已经不会去初始化 Loom 了（只 include 当前这一个加载器子项目），
那行只是 Gradle 的配置输出。

**Q：1.19.2 提示 ForgeGradle / Gradle 相关错误**
1.19.2 用的 ForgeGradle 5 只支持 Gradle 7，`matrix.properties` 里已经为它单独指定了
`1.19.2.gradle=7.5.1`，脚本会自动下载一份到 `gradle-dist/`。这是正常的，不是出错。

**Q：双击 build.bat 提示 “Java was not found”**
没装 JDK 或没加进 PATH。装 [Adoptium JDK 17](https://adoptium.net/)（装的时候勾 “Set JAVA_HOME variable”），
装完重新双击。

**Q：Gradle 下载不动 / 下载失败**
脚本依次尝试腾讯云、阿里云、华为云、官方四个源。都失败的话手动下载
[gradle-8.10-bin.zip](https://services.gradle.org/distributions/gradle-8.10-bin.zip)，
解压到项目根目录，保证存在 `gradle-dist\gradle-8.10\bin\gradle.bat`，再双击 build.bat。

**Q：所有组合都报 “pluginManagement {} block must appear before any other statements”**
`settings.gradle` 里 `pluginManagement {}` 前面不能有任何语句，这是 Gradle 的硬性要求。
现在版本矩阵只在 `pluginManagement` 的块体内部读取，已经处理过了——如果你自己改过
`settings.gradle`，检查一下是不是在它前面加了 `def` 之类的语句。

**Q：某个组合报 “Plugin ... requires at least Gradle X.X”**
该 MC 版本对应的工具链需要更高的 Gradle。在 `matrix.properties` 里加一行即可，
脚本会自动下载对应版本（每个版本约 130MB，只下一次）：

```properties
1.21.5.gradle=8.13          :: 整个 1.21.5 都用 Gradle 8.13
1.21.11.fabric.gradle=9.2   :: 只让 1.21.11 的 fabric 用 Gradle 9.2
```

查找顺序是 `<mc>.<loader>.gradle` → `<mc>.gradle` → `default.gradle`。

**Q：为什么 1.21.11 没有 fabric？**
1.21.11 的 Fabric 需要 Loom 1.14，而 Loom 1.14 要求 Gradle 9.2+；同一 MC 版本下的
Forge / NeoForge 又需要 Gradle 8.x，两者无法共用一套 Gradle。默认就先不带这个组合。
确实需要的话按 `matrix.properties` 里的注释改三行，然后单独构建 `build.bat 1.21.11 fabric`。

**Q：提示 “Build finished. 0 combination(s) succeeded”，但下面没有 Failed 列表**
说明一个组合都没跑起来，通常是 `matrix.properties` 没被读到。脚本开头会检查它的大小，
小于 100 字节就直接报错——这时重新解压一份完整的工程压缩包即可。
也可以用 `build.bat --list` 确认矩阵到底解析出了几个组合。

**Q：构建报 “Could not find net.minecraftforge:forge:1.20.1-xx” / Loom 报不支持该 MC 版本**
版本号不对。运行 `python3 tools/update_versions.py`，或去
[Forge 官网](https://files.minecraftforge.net/) / [NeoForge](https://neoforged.net/) 查对应 MC 的最新版号手改。

**Q：1.21.11 的 Forge 构建失败**
Forge 对最新 MC 的支持常常滞后。要么等，要么把该版本 `loaders` 里的 `forge` 去掉，只留 `fabric neoforge`。

**Q：`NeoGradle` 要求 `neoForge { version = ... }` 写法**
`neoforge/build.gradle` 里已经留好注释块，按注释切换即可。

**Q：日志里说“无法获取当前登录玩家的 UUID”**
理论上不会。若出现，说明启动器没传用户信息（部分离线启动器）。可用命令行模式修：
`java -jar sharedplayerdata-core.jar <.minecraft目录> <你的UUID>`
离线模式玩家的 UUID = `MD5("OfflinePlayer:" + 游戏名)`，网上搜 “Minecraft offline UUID calculator” 可算。

**Q：装了模组后打开存档，我的东西还是没了？**
* 如果**在装模组之前**你就已经打开过那个存档，你自己的数据当时就被房主数据覆盖了。
  先去 `存档目录/playerdata/` 找 `<你的UUID>.dat_old`（上一次保存的备份），把 `.dat_old` 改回 `.dat`；
  或者找 `<你的UUID>.dat.spd_backup`。
* 末影箱、进度、统计也在玩家数据里，同样会受影响；普通箱子属于世界数据，不受影响。

**Q：这模组会不会动我自己的存档？**
不会。只有当 `level.dat` 里那份数据的 UUID **明确不等于你**时才会摘除；属于自己的原样保留。
写回前还会先备份你自己那份，且写入用「临时文件 + 原子替换」，不会把 `level.dat` 写坏。

**Q：局域网联机时其他人需要装吗？**
严格说只有「可能会拿到存档、并以单人方式打开它」的人需要装。但所有人都装最省事，且它只在客户端启动阶段
扫一遍 saves 目录，之后完全不参与游戏逻辑，没有性能影响。

**Q：支持基岩版吗？**
不支持，也不需要——基岩版用 `.mcworld` 导出/导入，玩家数据机制与 Java 版不同。

---

## 九、不装模组的手动修法

1. 先备份整个存档文件夹。
2. 用 [NBTExplorer](https://github.com/jaquadro/NBTExplorer) 打开存档根目录的 `level.dat`：
   * 1.21.11 及更早：删除 `Data → Player` 整个复合标签；
   * 26.1 及更新：删除或改掉 `Data → singleplayer_uuid`。
3. 保存，删掉同目录的 `level.dat_old`，再进游戏。
4. 换房主前，**所有人把随身物品放进普通箱子**（别放末影箱，末影箱属于玩家数据）。

命令行版（本项目的 core jar 也能干这事）：

```bash
java -jar sharedplayerdata-core.jar "/Users/me/Library/Application Support/minecraft" 069a79f4-44e9-4726-a5be-fca90e38aaf5
```

---

## 十、数据安全策略（重要）

本模组动的是别人的存档，所以一切以「宁可不动，也不能丢」为准：

* **先落地，再删除。** `level.dat` 里内嵌的玩家数据只有确认已经安全写入
  `playerdata/<UUID>.dat`（且能再读回来），才会被移除。归档失败就保留内嵌数据。
* **26.1 格式同理。** 只删 `singleplayer_uuid` 指针之前，必须确认
  `players/data/<UUID>.dat` 真的存在，否则指针一删数据就失联了。
* **备份只写一次。** 所有备份都走 `backupOnce()`，只在备份不存在时才写，
  反复操作也不会把最原始那份覆盖成后来被改过的版本。
* **全零 UUID 一律拒绝。** `00000000-0000-...` 不是合法身份，拿它比对会把
  所有人的数据都判成「别人的」而删掉，结果是所有人进存档都变空号。
* **世界中不写盘。** 所有修正只在主菜单进行——在世界中时游戏正在读写
  `level.dat` 和玩家数据文件，抢文件有损坏风险。
* **`isProbablyEmpty()` 判据较宽。** 背包、末影箱、经验、血量、饱食度、
  选中栏、药水效果、能力值，任何一项偏离新玩家默认值就算「玩过」，
  不会被当成空号覆盖。

## 十一、已知限制

* 只处理「单人 / 局域网房主」场景。玩家数据本来就是按 UUID 分开存的纯净服务端不受此问题影响。
* 必须在**第一次打开收到的存档之前**就装好模组，否则你自己的数据可能已被覆盖（见上文恢复办法）。
* 扫描发生在**游戏启动阶段**，之后只要你在主菜单就会每 15 秒自动重扫一次，
  所以运行中导入的新存档回到主菜单即可生效，不必重启游戏。
  在世界中时不会动任何文件（那时游戏正在读写 level.dat）。
* Forge / NeoForge 侧在 **`FMLLoadCompleteEvent`（mod 加载完成）之后**才启动扫描线程，
  再由线程轮询等待 Minecraft 实例就绪（最长 3 分钟）。
  **绝不能**在 `@Mod` 构造函数里启动：那里还在 mod loading 阶段，此时去调
  `Minecraft.getInstance()` 会从后台线程触发 Minecraft 类的静态初始化，与并行加载
  的 mod worker 争抢类初始化锁而死锁，表现为游戏永远停在 Loading mods。
  大型整合包几乎必然踩中，mod 少时才可能侥幸不发作。
  若该事件在某个版本上注册不上，会退化为「延迟 60 秒再启动」，同样安全。
  注意这是**编译期**依赖：万一某个版本连 `FMLLoadCompleteEvent` 这个类都没有，
  构建会直接报错；此时把主类里的注册删掉、改成直接调 `ClientBootstrap.startDelayed()`
  就能编过，代价只是扫描要等 60 秒才开始。
* `/spd` 命令通过反射挂载，属于“能挂就挂”：
  Fabric 需要装了 Fabric API；Forge / NeoForge 需要存在 `RegisterClientCommandsEvent`。
  挂不上只是没有命令，日志里会写明原因，存档修正不受任何影响。
* 扫描结果按 `level.dat` 的「修改时间 + 大小」做指纹缓存，指纹没变就复用上次结果，
  所以看门狗每 15 秒高频扫描时几乎不耗资源。

## 十二、提交到 GitHub

### 一键提交（Windows）

```bat
push.bat                  :: 提示输入 commit message，然后提交并推送
push.bat "fix something"  :: 直接带 message
push.bat --dry-run        :: 只看会提交什么，不提交
```

脚本会自动完成：没有仓库就 `git init` → 配置 `origin` → 拉取远端并合并 → 提交 → 推送。

> **认证**：GitHub 已不接受账号密码。
>
> * **HTTPS + Token**（推荐）：GitHub → Settings → Developer settings →
>   Personal access tokens → Tokens (classic)，勾选 `repo`。
>   第一次推送时 Git Credential Manager 会记住它。
> * **SSH**：把仓库地址换成 SSH 形式再运行：
>   ```bat
>   set SPD_REPO_URL=git@github.com:radix-angelicae/shared-playerdata.git
>   push.bat
>   ```
>
> 仓库里已有一份 `LICENSE`（版权属 `radix-angelicae`）。脚本在首次同步时会**采用远端那份**
> 覆盖本地的，避免两个互不相干的提交历史在 LICENSE 上冲突。想保留自己的版权，
> 首次推送成功后改 `LICENSE` 再提交一次即可。

### GitHub Actions 构建（默认不自动跑）

`.github/workflows/build.yml` **只由手动触发**，push / PR 不会自动跑。

原因是 `matrix.properties` 里的 Forge / NeoForge / Loom 版本号大多是按经验填的，
未经实机构建验证，自动跑很容易大面积报红。等各组合在本地验证过之后，
把 workflow 里 `push` / `pull_request` 那两行的注释去掉即可恢复自动触发。

手动触发：Actions → Build → Run workflow，两个可选输入：

| 输入 | 说明 |
|---|---|
| `mc` | 只构建某个 MC 版本（如 `1.21.5`），留空 = 全部**已选中的**组合 |
| `full` | 勾选后连**未验证**的组合一起构建；不勾 = 只跑 `safe: true` 的组合 |

`safe: true` 标的是本机已产出过 jar 的组合（当前 3 个：1.20.1/forge、1.21.1/fabric、
1.21.1/neoforge）。每验证通一个，就把 `matrix` 里对应那行的 `safe` 改成 `true`。

> **坑**：`matrix` 上下文**不能**用在 job 级 `if` 里（`if` 在矩阵展开之前求值），
> 否则整个 workflow 在解析阶段就报 `Unrecognized named-value: 'matrix'`，
> 18 个 job 全部失败。所以组合过滤做成了第一个 step（gate），
> 产出 `run=true/false` 给后续 step 用。

几个实现上的取舍：

* **用 `windows-latest`**：本项目虽然也提供了 `build.sh`，但只在 Windows 上做过
  实机构建验证；用 Windows runner 能保证 CI 与主要开发环境一致。
  （`build.sh` 的参数与 `build.bat` 完全对齐，换 runner 只需要改这一行。）
* **CI 里去掉代理**：`gradle.properties` 里的 `systemProp.*.proxy*` 指向 `127.0.0.1:10808`
  （你本机的代理），runner 上没有。workflow 会先删掉这些行，
  再用 `build.bat <mc> <loader> --no-proxy --no-pause` 构建。
  `--no-proxy` 是为此新增的参数，只影响 Gradle 分发包的下载，本地行为不变。
* **缓存**：`gradle-dist/` 与 `~/.gradle/caches` 都做了缓存。
  依赖缓存按「MC 版本 + 加载器」分开存——Loom 反编译和 Forge userdev 的产物很大，
  混在一起会互相挤掉。
* **`fail-fast: false`**：某个组合失败不取消其他组合，正好一眼看出是哪几个版本有问题。

验证版本号时最省时间的做法：本机改完 `matrix.properties`，提交后到 Actions →
Build → Run workflow，`mc` 填那一个版本即可只重试它，不必等全量跑完。

## 十三、许可

MIT。
