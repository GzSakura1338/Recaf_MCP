# Linux 无头运行（headless）

Recaf 4 自带无头模式：`--headless` 跳过整个 JavaFX UI，`--idle` 让进程常驻，
专门给"插件提供的服务"用。Recaf MCP 插件就是这样一个服务，所以 Linux 服务器、
容器、CI 里不需要 X server、不需要 Xvfb、也不需要 VNC —— 只要有 JDK 25 和 `recaf.jar`。

```text
MCP 客户端 ──HTTP──> Python MCP 服务 (127.0.0.1:8751)
                             │HTTP
                             ▼
                 Recaf 插件 (127.0.0.1:8750)  ← 跑在 recaf --headless --idle 里
```

GUI 模式照旧可用：插件跑在有 JavaFX UI 的 Recaf 里时，`fetch_current_class`、
`get_selected_text` 这些"看界面"的工具才有内容。

---

## 1. 一次性安装

```bash
cd /path/to/Recaf_MCP

# Recaf 4 发行版 jar（注意选对架构：linux-aarch / linux-86-x64）
curl -L -o libs/recaf.jar \
  https://github.com/Col-E/Recaf/releases/download/4.0.0-alpha/recaf-4x-alpha-linux-aarch.jar

./scripts/linux/install.sh
```

`install.sh` 会：

1. 用 `libs/recaf.jar` 编译插件（`./gradlew jar`）；
2. 把 `build/libs/recaf-mcp-plugin-*.jar` 复制到 `~/.config/Recaf/plugins/`；
3. 建 `.venv` 并安装 `requirements.txt`（fastmcp / httpx / requests）。

不想装进 Recaf 插件目录也行，下面的脚本支持 `--plugin-dir` 直接指向 `build/libs`。

---

## 2. 启动

```bash
# 无头 Recaf + 插件（默认 127.0.0.1:8750），可顺手打开一个目标 jar
./scripts/linux/start_recaf_headless.sh start --input /path/to/target.jar

# Python MCP 服务（默认 127.0.0.1:8751/mcp）
./scripts/linux/start_mcp_http.sh start

# 状态 / 停止
./scripts/linux/start_recaf_headless.sh status
./scripts/linux/start_mcp_http.sh status
./scripts/linux/start_recaf_headless.sh stop
./scripts/linux/start_mcp_http.sh stop
```

MCP 客户端配置：

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

日志与 pid 文件：

| 文件 | 内容 |
|---|---|
| `logs/recaf-headless.log` | 无头 Recaf 的 stdout/stderr |
| `.run/recaf-headless.pid` | 无头 Recaf 的 pid |
| `logs/mcp-http.log` | MCP 服务日志 |
| `.run/mcp-http.pid` | MCP 服务 pid |

两个脚本都支持 `start | stop | status | restart`，以及 `--foreground`
（前台跑，Ctrl-C 结束，适合 systemd `Type=simple` / docker `ENTRYPOINT`）。

### `start_recaf_headless.sh` 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--jar PATH` | `$RECAF_JAR`、`libs/recaf.jar` | `recaf.jar` |
| `--plugin-dir DIR` | `$RECAF_PLUGIN_DIR`、`build/libs` | 作为 `-r` 传给 Recaf 的插件目录 |
| `--data-dir DIR` | `$RECAF_DATA_DIR`（空 = Recaf 默认） | `-d`，用于隔离多个实例 |
| `--input FILE` | 无 | `-i`，启动即打开的工作区 |
| `--java PATH` | `$RECAF_JAVA`、`JAVA_HOME`、`PATH` | java 可执行文件 |
| `--jvm-args "..."` | `$RECAF_JVM_ARGS` | 额外 JVM 参数，如 `-Xmx4g` |
| `--port N` | `8750` | 期望的插件端口，仅用于健康检查 |
| `--timeout N` | `90` | 等 `/health` 的秒数 |

### `start_mcp_http.sh` 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--host` / `--port` | `127.0.0.1` / `8751` | MCP 监听地址 |
| `--recaf-host` / `--recaf-port` | `127.0.0.1` / `8750` | 插件地址与扫描起点 |
| `--headless` / `--gui` | 自动 | 新开 Recaf 进程时用哪种模式 |
| `--recaf-jar`、`--recaf-plugins-dir`、`--recaf-data-dir` | 见上文 | 传给 `open_recaf_window` |
| `--no-plugin-check` | 关闭 | 插件还没起来也先启动 MCP 服务 |
| `--foreground` | 关闭 | 前台运行 |

脚本在启动 MCP 服务前会先探 `/health`（和 PowerShell 版一致），插件没起来就直接报错，
省得客户端连上一个空壳服务。

---

## 3. 无头模式下插件的行为

无头 Recaf 没有窗口、没有编辑器标签页，所以：

| 工具 | 无头时 |
|---|---|
| `get_all_classes`、`get_class_source`、`get_method_bytecode`、搜索、xref、重命名、导出、字节码编辑…… | 正常 |
| `fetch_current_class`、`get_selected_text` | 返回 `ui_available: false` 和原因，不再抛 500 |
| `open_workspace`、`open_recaf_window` | 正常（新进程默认也走无头） |

`GET /instance` 现在会带上模式信息，`list_recaf_windows` 也能看到：

```json
{
  "instance_id": "…",
  "pid": 131748,
  "port": 8750,
  "headless": true,
  "ui_available": false,
  "has_workspace": true,
  "jar_name": "target.jar"
}
```

插件配置可以直接用环境变量（systemd / docker 友好）：

| 变量 | 作用 |
|---|---|
| `RECAF_MCP_HOST` | 插件绑定地址，默认 `127.0.0.1` |
| `RECAF_MCP_PORT` | 插件起始端口，默认 `8750` |

系统属性仍然优先：`-Drecaf.mcp.host=… -Drecaf.mcp.port=…`。

---

## 4. MCP 服务端参数（无头相关）

```bash
.venv/bin/python recaf_mcp_server.py --http --port 8751 \
  --headless \
  --recaf-jar /path/to/recaf.jar \
  --recaf-plugins-dir /path/to/build/libs \
  --recaf-data-dir /tmp/recaf-data \
  --java /usr/lib/jvm/temurin-25/bin/java \
  --java-args "-Xmx4g" \
  --recaf-launch-log logs/recaf-launch.log
```

- `--headless`：`open_recaf_window` 起新进程时加 `--headless --idle`。
- `--gui`：强制 GUI（`--headless` 的反面）。
- 不传时自动判断：**Linux 且没有 `DISPLAY` → 无头**，否则 GUI。
- 已有窗口时默认沿用它的启动方式（读进程命令行，`/proc` 读不到时退回 `ps -o args=`）。
- 对应环境变量：`RECAF_HEADLESS`、`RECAF_PLUGIN_DIR`、`RECAF_DATA_DIR`、`RECAF_JAVA`、
  `RECAF_JAVA_ARGS`、`RECAF_LAUNCH_LOG`。

---

## 5. systemd（用户级）

```bash
cp contrib/systemd/recaf-mcp-headless.service ~/.config/systemd/user/
cp contrib/systemd/recaf-mcp-server.service   ~/.config/systemd/user/
# 按自己的路径改 WorkingDirectory / ExecStart
systemctl --user daemon-reload
systemctl --user enable --now recaf-mcp-headless recaf-mcp-server
journalctl --user -u recaf-mcp-headless -f
```

单位文件里没有任何 `DISPLAY`/`Xvfb` 依赖，`Restart=on-failure` 会在 Recaf 崩溃后拉起。

---

## 6. 容器 / CI

```dockerfile
FROM eclipse-temurin:25-jdk
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/recaf-mcp
COPY . .
RUN curl -L -o libs/recaf.jar https://github.com/Col-E/Recaf/releases/download/4.0.0-alpha/recaf-4x-alpha-linux-86-x64.jar \
    && ./scripts/linux/install.sh
EXPOSE 8751
CMD ["./scripts/linux/start_mcp_http.sh", "start", "--foreground"]
```

CI 里只想验证链路时：

```bash
./scripts/linux/smoke_test.sh --with-mcp
```

只测"启动参数怎么拼"的单元测试（不需要 Recaf、不需要显示）：

```bash
.venv/bin/python -m unittest discover -s mcp-server/tests -v
```

`smoke_test.sh --with-mcp` 会自己起一个一次性的无头 Recaf（独立数据目录、独立端口），依次验证
`/health`、`/instance`、`/workspace/open`、`/all-classes`、`/class-source`、
无头下的 `get_selected_text` 降级，以及（`--with-mcp`）通过 MCP 客户端实际调用工具。

---

## 7. 端口与多实例

- 每个 Recaf 进程从 `8750` 起绑定第一个空闲端口，跳过 `8751`（MCP 服务），最多到 `8769`。
- 想并排跑多个无头 Recaf：给每个实例不同的 `--port`（期望端口）和 `--data-dir`，
  再用 `MCP 服务 --recaf-port` 或 `list_recaf_windows` / `switch_recaf_window` 选择。

---

## 8. 排错

| 现象 | 原因 / 处理 |
|---|---|
| `java: command not found` | 装 JDK 25，或用 `--java` / `RECAF_JAVA` 指定 |
| `plugin did not answer within 90s` | 看 `logs/recaf-headless.log`；常见是堆内存不足或 `libs/recaf.jar` 架构不对 |
| `no recaf-mcp-plugin*.jar found` | 先 `./gradlew jar`，或 `install.sh` |
| MCP 客户端连上但工具报 "no Recaf window found" | Recaf 没起或插件没加载；`curl 127.0.0.1:8750/health` 应返回 `ok` |
| `current-class` 返回 `ui_available: false` | 正常：无头模式没有 UI；请显式传 `class_name` |
| `Address already in use` | 8750–8769 被占满，先停掉多余的 Recaf 实例 |
