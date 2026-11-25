<div align="center">

# Recaf MCP

> *把 Recaf 4 变成 LLM 可以调用的 API*

[![Java](https://img.shields.io/badge/Java-25-ED8106)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.10+-3776AB)](https://www.python.org/)
[![Recaf](https://img.shields.io/badge/Recaf-4.x-2D9CDB)](https://www.coley.software/recaf4/)
[![MCP](https://img.shields.io/badge/MCP-stdio%20%7C%20HTTP-8B5CF6)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-Apache--2.0-3DA639)](LICENSE)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-Join-0099FF?logo=tencent-qq&logoColor=white)](https://qm.qq.com/q/KWcs2UBtYK)

[English](README.md) · [无头运行指南](docs/linux-headless.md) · [发布检查清单](PUBLISHING.md)

</div>

---

## 这是什么

**Recaf MCP** 把 [Recaf 4](https://github.com/Col-E/Recaf) 变成一个 **70 个工具的 MCP 服务**：
模型可以列出类、反编译方法、搜索字符串与指令、查交叉引用、批量重命名、导出 jar，
也可以直接读写字节码（指令级增删改、JASM 汇编、访问标志、方法/字段增删、try-catch、替换类字节）。

```text
MCP 客户端 (DSH / Claude Code / IDE)
        │  stdio（宿主拉起进程） 或 HTTP streamable
        ▼
Python MCP 服务  ──HTTP──▶  Recaf 插件（运行在 Recaf 进程内）
  127.0.0.1:8751              127.0.0.1:8750
        │                          │
        └── 自动拉起 ──────────────▶ recaf --headless --idle
```

- **插件**用纯 JDK 的 HTTP 服务暴露 Recaf 内部 API（工作区、搜索、反编译、ASM 编辑）。
- **MCP 服务**把 HTTP API 映射成 MCP 工具，并负责发现/编号/切换多个 Recaf 窗口。
- **Recaf 4 自带 `--headless --idle` 无头模式**，所以 Linux 服务器、容器、CI 不需要 X server，
  也不需要 Xvfb —— 这是本项目相对图形界面的关键区别。

## 特性

| 特性 | 说明 |
|---|---|
| 70 个工具 | 工作区、类、方法、搜索、xref、重命名、反编译、继承、资源、导出、字节码编辑 |
| 原生无头 | `recaf --headless --idle`，无需 DISPLAY；无头下 UI 相关工具优雅降级 |
| 自动拉起 | MCP 服务发现没有窗口时自己启动无头 Recaf（`--auto-start-recaf`） |
| 空闲回收 | 只保留 Python 常驻：Recaf 用完空闲 N 秒后自动关掉（`--recaf-idle-stop`），下次调用再拉起 |
| 单实例 | Linux 默认只跑一个 Recaf，`open_recaf_window` 不会开第二个（`--multi-recaf` 解除） |
| 多窗口 | 一个服务管理多个 Recaf 进程，`<编号>:<jar 名>` 定位，不会改错 jar |
| 三种接入 | stdio（宿主拉起）/ streamable HTTP（常驻服务）/ 现成的 DSH bundle |
| 双平台脚本 | Windows PowerShell 与 Linux bash 对等：启动、停止、状态、安装、自检 |
| 测试齐全 | 70 工具矩阵（110 次调用）、Python 单测、无头冒烟测试、自动拉起测试 |

## 环境要求

- **JDK 25**（Recaf 4 与插件编译目标）
- **Python 3.10+**（`fastmcp>=3.0.2`、`httpx`、`requests`）
- **`recaf.jar`**：仓库已内置 `libs/recaf.jar`（Recaf 4.0.0-alpha，linux-aarch 构建）。
  其它平台从 [Recaf Releases](https://github.com/Col-E/Recaf/releases) 下载对应 jar 覆盖即可，
  或用 `RECAF_JAR` 指向别处的 jar。

| 场景 | 需要图形界面 | 推荐入口 |
|---|---|---|
| Linux 无头（服务器 / 容器 / CI） | 否 | `scripts/linux/install.sh` |
| Windows / Linux 桌面 GUI | 是 | `gradlew jar` + Recaf 插件目录 |
| 由 MCP 宿主自己拉起 | 否 | stdio 配置 / `contrib/dsh-bundle` |

## 快速开始

### A. Linux 无头（最快）

```bash
# 1) 编译插件、装进 ~/.config/Recaf/plugins、建 .venv 装依赖
./scripts/linux/install.sh

# 2) 启动无头 Recaf（可在启动时打开目标 jar）
./scripts/linux/start_recaf_headless.sh start --input /path/to/target.jar

# 3) 启动 MCP 服务
./scripts/linux/start_mcp_http.sh start
```

```bash
./scripts/linux/start_recaf_headless.sh status   # 状态
./scripts/linux/start_mcp_http.sh status
./scripts/linux/start_recaf_headless.sh stop     # 停止
```

日志在 `logs/`，pid 在 `.run/`。完整参数、systemd、Docker、排错见
**[docs/linux-headless.md](docs/linux-headless.md)**。

### B. Windows / 桌面 GUI

```powershell
# 1) 构建插件（用仓库自带的 libs/recaf.jar，或用 RECAF_JAR 指定）
.\gradlew.bat jar
# 2) 复制到 %APPDATA%\Recaf\plugins\ 后启动 Recaf
# 3) 安装 Python 依赖并启动 MCP 服务
pip install -r requirements.txt
.\start_recaf_mcp_http.ps1
```

启动成功时 Recaf 日志里会出现：

```text
Recaf MCP plugin listening on http://127.0.0.1:8750
```

### C. 让 MCP 宿主自己拉起（stdio / DSH）

宿主负责启动 MCP 服务，服务负责启动 Recaf，一条配置即可：

```json
{
  "mcpServers": {
    "recaf": {
      "type": "stdio",
      "command": "/path/to/Recaf_MCP/.venv/bin/python",
      "args": [
        "/path/to/Recaf_MCP/recaf_mcp_server.py",
        "--headless", "--auto-start-recaf",
        "--recaf-jar", "/path/to/Recaf_MCP/libs/recaf.jar",
        "--recaf-plugins-dir", "/path/to/Recaf_MCP/build/libs"
      ]
    }
  }
}
```

DSH 用户可直接装仓库里的配置型 bundle：

```bash
dsh plugin install "/path/to/Recaf_MCP/contrib/dsh-bundle"
# 或 Web UI：Plugins → Add plugin → 选择该目录
```

工具名形如 `mcp__recaf__get_class_source`。第一次调用会多等几秒（Recaf 正在启动 + 导入 jar）。

### MCP 客户端配置（HTTP）

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:8751/mcp"
    }
  }
}
```

## 工具总览（70 个）

| 分组 | 数量 | 工具 |
|---|---|---|
| 窗口 | 3 | `list_recaf_windows`（返回 `single_recaf` 标记）`switch_recaf_window` `open_recaf_window`（单实例下拒绝第二个） |
| 工作区 | 7 | `get_workspace_info` `open_workspace` `close_workspace` `add_supporting_resource` `list_supporting_resources` `fetch_current_class` `get_selected_text` |
| 类 | 9 | `get_all_classes` `get_class_info` `get_class_source` `get_bytecode_of_class` `get_methods_of_class` `get_fields_of_class` `get_inner_classes` `get_annotations_of_class` `get_raw_class_bytes` |
| 方法 | 3 | `get_method_by_name` `get_method_bytecode` `get_method_info` |
| 搜索 | 5 | `search_classes_by_name` `search_members_by_name` `search_strings` `search_numbers` `search_instructions` |
| 交叉引用 | 6 | `get_xrefs_to_class` `get_xrefs_to_method` `get_xrefs_to_field` `get_callers_of_method` `get_callees_of_method` `get_overrides_of_method` |
| 重命名 | 6 | `rename_class` `rename_method` `rename_field` `rename_package` `rename_local_variable` `apply_mappings` |
| 反编译器 | 3 | `list_decompilers` `set_active_decompiler` `decompile_class_with` |
| 继承 | 5 | `get_superclasses` `get_interfaces` `get_direct_subclasses` `get_all_subclasses` `get_implementors` |
| 资源 | 4 | `get_all_file_names` `get_file_content` `get_manifest` `get_strings_from_resources` |
| 导出 | 3 | `export_workspace` `get_modified_classes` `revert_class` |
| 字节码编辑 | 16 | `list_method_instructions` `replace_instruction` `insert_instruction` `remove_instruction` `assemble_method` `replace_method_body` `edit_class_access` `edit_method_access` `edit_field_access` `add_method` `remove_method` `add_field` `remove_field` `set_try_catch_blocks` `replace_class_bytes` `save_workspace` |

典型流程：

```text
get_all_classes → get_class_source → get_method_bytecode
  → replace_method_body / assemble_method → get_modified_classes
  → export_workspace（或 save_workspace）→ revert_class（想撤销时）
```

## 无头运行

Linux 上没有显示设备时，一切都走 Recaf 原生无头模式：

- 启动参数：`recaf.jar --headless --idle -r <插件目录> -d <数据目录> [-i <jar>]`
- 插件正常提供 HTTP API；`/instance` 会返回 `headless: true`、`ui_available: false`
- `fetch_current_class` / `get_selected_text` 依赖编辑器界面，无头下返回
  `ui_available: false` 和说明而不是报错；其余工具全部可用
- `--auto-start-recaf`：任一工具调用发现没有任何窗口时，自动拉起一个（无显示时自动用无头），
  失败后 60 秒内不重试
- `--recaf-idle-stop N`：本服务拉起的 Recaf 在 N 秒没有 Recaf 工具调用后停止，服务退出时也一并停止，
  所以**常驻的只有 Python 进程**；下次调用会重新拉起（几秒）。只回收本服务启动的进程，
  你自己用脚本/GUI 开的 Recaf 不会被碰。所有权记在 `recaf-mcp-owned.json`（含 owner pid），
  宿主重载后新服务能认领上一任留下的 Recaf，但**另一个还活着的服务**拥有的进程不会被抢

细节（参数表、systemd 单元、Dockerfile、端口、排错）见
**[docs/linux-headless.md](docs/linux-headless.md)**。

## 单实例与多窗口

**Linux 默认单实例**：一个 MCP 服务最多跑一个 Recaf。已经有窗口时：
`open_recaf_window` 直接返回错误（提示复用或先停止），自动拉起也只在零窗口时发生。
一个窗口还要占几百 MB 堆内存，多开没有收益。真的需要多实例时：

```bash
python recaf_mcp_server.py --http --multi-recaf   # 或 RECAF_SINGLE=0
```

`scripts/linux/start_recaf_headless.sh` 也按单实例设计：检测到 8750–8769 已有插件在应答就拒绝启动。

非单实例模式（或 `--multi-recaf`）下仍是多窗口模型：

可以同时跑多个 Recaf 进程（GUI 或无头），每个都加载插件并从 `8750` 起绑定第一个空闲端口。

| 工具 | 作用 |
|---|---|
| `list_recaf_windows` | 列出窗口，形如 `1:client.jar`、`2:server.jar`，并标出默认窗口与 `headless` 标记 |
| `switch_recaf_window(window)` | 设为默认，之后不带 `window` 的调用都走它 |
| `open_recaf_window(jar_path?, wait_seconds?)` | 再开一个 Recaf（模式跟随 `--headless/--gui` 或已有窗口），可顺带打开 jar |

其它工具都有可选参数 `window`：`"2"`、`"2:client.jar"`，或 jar 名里能唯一匹配的一段。
编号后面带 jar 名时必须与实际一致，过期的引用会被拒绝，不会改错 jar。

## 运行开销

常驻时它只做一件事：等 MCP 请求。实测（8 核 ARM Linux，本机回环，`scripts/bench_python_overhead.py`）：

| 指标 | 数值 | 原因 |
|---|---|---|
| 空闲 CPU | 0.0–0.3% | 看门狗不轮询：`threading.Event` 一直等到空闲期限 |
| 常驻内存（RSS） | ~36 MB | 全程只用一个 HTTP 客户端，不再每次调用新建 |
| 单次工具调用 CPU | ~0.8 ms | 修复前 30 ms（每次调用都新建 `httpx.AsyncClient`，光 TLS 上下文就 18 ms） |
| 单次工具调用延迟 | ~0.8 ms | keep-alive 复用连接，不再每次握手 |
| 每次调用的端口探测 | 0 次 | 解析结果缓存 30 s；连接失败时失效并重试 |
| 列窗口（`list_recaf_windows`） | 0.002 ms | 状态查询直接读缓存；冷缓存一次扫描约 29 ms（压测前是每次调用重扫） |

做法：

- 所有工具调用共用一个 keep-alive `httpx.AsyncClient`（按事件循环复用，换循环才重建）；端口探测也复用同一个客户端。
- 窗口解析走乐观缓存：30 s 内复用上次的窗口列表、不扫端口；请求真的失败才 `invalidate()` 重试，所以不会用错窗口。
- 列窗口不再强制重扫：`list_recaf_windows` 是状态查询，读 3 s 内的缓存；真正用窗口的调用会证明它是否还活着。
- 扫描的单端口预算从 2 s 降到 0.75 s：同网段若有别的服务卡住，不会把每次扫描拖到 2 s。
- 空闲回收事件驱动：有调用就 `set()` 事件重算期限，没有调用就一次长等待；停止失败退避 30 s，不忙等。
- 想量化某次优化：`python scripts/bench_ab.py <优化前的 checkout> .`（两侧跑同一 harness，直接给出百分比）：
  CPU/调用 **−96.3%**、延迟/调用 **−96.2%**、`list_recaf_windows` **−100%**（约 1.4 万倍）、RSS **−12.8%**；
  真机链路实测 `list_recaf_windows` 33.3 ms → 2.4 ms（−92.7%）、`get_workspace_info` 26.3 ms → 4.8 ms（−81.7%）
- 可复现：`python scripts/bench_python_overhead.py`（假插件只答 `/instance`、`/health`、`/class-info`，量每调用 CPU / 内存 / 连接数 / 空闲 CPU）。

## 配置参考

### MCP 服务命令行

| 参数 | 默认 | 说明 |
|---|---|---|
| `--http` | 关 | 用 streamable HTTP 提供 MCP；不加则走 stdio |
| `--host` / `--port` | `127.0.0.1` / `8751` | MCP 监听地址与端口（非本机监听无鉴权，见[安全](#安全)） |
| `--recaf-host` / `--recaf-port` | `127.0.0.1` / `8750` | 插件地址与扫描起点（扫描 20 个端口） |
| `--recaf-jar` | `$RECAF_JAR`、`libs/recaf.jar` | `open_recaf_window` / 自动拉起用的 recaf.jar |
| `--headless` / `--gui` | 自动 | 新进程用无头还是 GUI；默认 Linux 无 `DISPLAY` 时无头 |
| `--recaf-plugins-dir` | `$RECAF_PLUGIN_DIR`、`build/libs` | 传给无头 Recaf 的 `-r` |
| `--recaf-data-dir` | `$RECAF_DATA_DIR` | 传给无头 Recaf 的 `-d` |
| `--java` / `--java-args` | `$RECAF_JAVA` / `$RECAF_JAVA_ARGS` | java 路径与额外 JVM 参数 |
| `--auto-start-recaf` | 关 | 没有窗口时自动拉起（`--no-auto-start-recaf` 可强制关闭） |
| `--auto-start-wait` | `90` | 自动拉起时等插件就绪的秒数 |
| `--single-recaf` / `--multi-recaf` | Linux 单实例 | 是否允许同时多个 Recaf；默认 Linux 单实例 |
| `--recaf-idle-stop` | `0`（不回收） | 本服务启动的 Recaf 空闲这么多秒后停止，只留 Python 常驻；`0` 表示一直保留 |
| `--recaf-launch-log` | `$RECAF_LAUNCH_LOG` | 把新进程输出追加到文件（调试无头启动很有用） |

### 环境变量

| 变量 | 作用 |
|---|---|
| `RECAF_JAR` | recaf.jar 路径（覆盖 `libs/recaf.jar`） |
| `RECAF_PLUGIN_DIR` / `RECAF_PLUGIN_INSTALL_DIR` | 插件目录 / 安装目标目录 |
| `RECAF_DATA_DIR`、`RECAF_JAVA`、`RECAF_JVM_ARGS` | 无头实例的数据目录、java、JVM 参数 |
| `RECAF_MCP_HOST` / `RECAF_MCP_PORT` | 插件自身监听地址 / 起始端口（插件读取） |
| `RECAF_HOST` / `RECAF_PORT` | MCP 服务看到的插件地址 / 起始端口 |
| `RECAF_HEADLESS`、`RECAF_AUTO_START`、`RECAF_AUTO_START_WAIT` | 无头与自动拉起开关 |
| `RECAF_IDLE_STOP` | 空闲回收秒数（等价 `--recaf-idle-stop`） |
| `RECAF_SINGLE` | `1`/`0` 覆盖单实例默认值 |
| `RECAF_MCP_HTTP_HOST` / `RECAF_MCP_HTTP_PORT` | Linux 脚本里 MCP 服务的监听地址 / 端口 |

### 端口

| 组件 | 地址 |
|---|---|
| 插件 HTTP | 第一个 Recaf 用 `127.0.0.1:8750`，之后依次取下一个空闲端口，最多到 `8769`，跳过 `8751` |
| MCP HTTP | `127.0.0.1:8751/mcp` |

健康检查：

```bash
curl http://127.0.0.1:8750/health      # ok
curl http://127.0.0.1:8750/instance    # pid / 端口 / 工作区 / headless 标记
```

## 验证与测试

| 命令 | 内容 |
|---|---|
| `./test.sh` | 构建 + Python 入口 + 脚本语法 + 单元测试 |
| `./test.sh --headless` | 再加无头端到端冒烟 |
| `./scripts/linux/smoke_test.sh --with-mcp` | 一次性无头 Recaf：`/health`、`/instance`、打开 jar、反编译、无头降级，并经 MCP 客户端调用工具 |
| `./scripts/linux/smoke_autostart.sh` | 零窗口 → 首次工具调用自动拉起 Recaf 并打开工作区 |
| `./scripts/linux/smoke_idle_stop.sh` | 只保留 Python：按需拉起、空闲回收、再次拉起、退出时清理 |
| `.venv/bin/python scripts/mcp_tool_matrix.py --jar <jar>` | **全量矩阵**：stdio 起服务，逐个调用 70 个工具（110 次调用），逐行 PASS/FAIL |

矩阵示例（`Nyx.jar`，75 MB Fabric mod）：

```text
MCP tool matrix: 110 calls, 110 passed, 0 failed
  windows / workspace / target jar (read-only) / search / xrefs+inheritance
  decompilers / scratch workspace / bytecode editing / refactor / export
all 70 tools behaved as expected
```

## 已知限制

1. `get_modified_classes` 在 `rename_package` / `apply_mappings` 之后会返回 0
   （Recaf 的映射应用会重建 bundle 并清空 dirty 历史），但内容确实与原始 jar 不同。
2. `assemble_method` 的 `LABEL` / `TRYCATCH` 文本指令会生成非法栈帧
   （ASM `NegativeArraySizeException`）：不含标签的指令体正常，try-catch 请用
   `set_try_catch_blocks` 作用在已有标签的方法上。
3. `search_strings` 只扫描 `LDC` 字符串常量，`invokedynamic` 字符串拼接的 recipe 不算命中。
4. 无头模式下 `fetch_current_class` / `get_selected_text` 没有数据（这是 UI 概念，属预期）。

## 排错

| 现象 | 处理 |
|---|---|
| `Missing recaf.jar` | 仓库已带 `libs/recaf.jar`；换平台时用 `RECAF_JAR` 指过去 |
| `plugin did not answer within 90s` | 看 `logs/recaf-headless.log`；常见是堆内存不足（`--jvm-args -Xmx4g`）或 jar 架构不对 |
| 工具报 `no Recaf window found` | Recaf 未启动或插件未加载；`curl 127.0.0.1:8750/health` 应为 `ok`，或加 `--auto-start-recaf` |
| `current-class` 返回 `ui_available: false` | 正常：无头模式没有 UI，请显式传 `class_name` |
| `Address already in use` | 8750–8769 已被占满，先停掉多余实例 |
| MCP 客户端看不到工具 | 检查 `--http`/stdio 配置与 `mcpServers` 名字；DSH 里新会话生效 |

## 安全

MCP 服务与插件都是**无鉴权的本地 HTTP**：

```bash
python recaf_mcp_server.py --http --host 0.0.0.0   # ⚠️ 暴露到网络
```

默认只监听 `127.0.0.1`。不要绑定非本机地址，除非已在防火墙 / VPN 之后。

## 仓库结构

```text
.
├── src/                      # Java 插件（HTTP 路由 + ASM 字节码编辑）
├── mcp-server/               # Python MCP 服务（工具定义 + 窗口发现）
├── scripts/
│   ├── linux/                # 无头启动 / 安装 / 冒烟 / 自检脚本
│   ├── mcp_tool_matrix.py    # 全量工具矩阵（70 工具 / 109 次调用）
│   ├── bench_python_overhead.py  # 常驻开销基准（CPU / 内存 / 连接数）
│   └── bench_ab.py             # 两次 checkout 的 A/B 对比（百分比）
├── contrib/
│   ├── dsh-bundle/           # DSH 配置型 bundle（stdio + 自动拉起）
│   └── systemd/              # systemd 用户单元模板
├── docs/linux-headless.md    # 无头运行完整指南
├── libs/recaf.jar            # 固定版本的构建依赖
├── recaf_mcp_server.py       # MCP 服务入口
├── requirements.txt
├── gradlew / build.gradle.kts
└── README.md / README_CN.md
```

## 开发

```bash
./gradlew jar                                  # 构建插件（依赖 libs/recaf.jar）
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
./test.sh --headless                           # 构建 + 单测 + 无头端到端
```

- 插件编译目标 Java 25（`build.gradle.kts` 的 toolchain），Gradle 9.4.1 wrapper。
- 单元测试在 `mcp-server/tests/`，覆盖无头启动参数与自动拉起决策。
- 发布流程见 [PUBLISHING.md](PUBLISHING.md)。

## 许可与致谢

[Apache-2.0](LICENSE)。

本项目是 [jadx-mcp-server](https://github.com/zinja-coder/jadx-mcp-server)（作者 Jafar Pathan / zinja-coder）的
Recaf 改写版本，沿用其 MCP 架构与工具命名风格。Recaf 本身由 [Col-E](https://github.com/Col-E) 开发。

<div align="center">
<sub>LLM 客户端 → MCP 服务 → Recaf 插件 → Recaf 工作区</sub>
</div>
