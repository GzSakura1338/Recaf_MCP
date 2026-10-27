<div align="center">

# Recaf MCP

> *「把 Recaf 变成 LLM 可调用的 API」*

[![Java](https://img.shields.io/badge/Java-25-ED8106)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.10+-3776AB)](https://www.python.org/)
[![Recaf](https://img.shields.io/badge/Recaf-4.x-2D9CDB)](https://www.coley.software/recaf4/)
[![MCP](https://img.shields.io/badge/MCP-HTTP-8B5CF6)](https://modelcontextprotocol.io/)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-Join-0099FF?logo=tencent-qq&logoColor=white)](https://qm.qq.com/q/KWcs2UBtYK)

<br>

[English](README.md)

<br>

**Recaf 4 MCP 桥接工具 — 通过 MCP 协议远程操作 Recaf 工作区进行 JVM 字节码分析与修改。**

<br>

Java 插件运行在 Recaf 内部，暴露本地 HTTP API；Python MCP 服务器将 API 转为 MCP 工具供 LLM 客户端调用。

<br>

[工具列表](#工具列表) · [快速开始](#快速开始) · [字节码编辑](#字节码编辑) · [安装插件](#安装插件)

</div>

---

## 工具列表

下面所有工具都带一个可选的 `window` 参数，见[多 Recaf 窗口](#多-recaf-窗口)。

### Recaf 窗口
`list_recaf_windows` `switch_recaf_window` `open_recaf_window`

### 工作区
`get_workspace_info` `open_workspace` `close_workspace` `add_supporting_resource` `list_supporting_resources` `fetch_current_class` `get_selected_text`

### 类
`get_all_classes` `get_class_info` `get_class_source` `get_bytecode_of_class` `get_methods_of_class` `get_fields_of_class` `get_inner_classes` `get_annotations_of_class` `get_raw_class_bytes`

### 方法
`get_method_by_name` `get_method_bytecode` `get_method_info`

### 搜索
`search_classes_by_name` `search_members_by_name` `search_strings` `search_numbers` `search_instructions`

### 交叉引用
`get_xrefs_to_class` `get_xrefs_to_method` `get_xrefs_to_field` `get_callers_of_method` `get_callees_of_method` `get_overrides_of_method`

### 重命名
`rename_class` `rename_method` `rename_field` `rename_package` `rename_local_variable` `apply_mappings`

### 反编译器
`list_decompilers` `set_active_decompiler` `decompile_class_with`

### 继承
`get_superclasses` `get_interfaces` `get_direct_subclasses` `get_all_subclasses` `get_implementors`

### 资源与导出
`get_all_file_names` `get_file_content` `get_manifest` `get_strings_from_resources` `export_workspace` `get_modified_classes` `revert_class`

---

## 字节码编辑

支持完整的字节码读写：指令级修改、JASM 文本汇编、访问标志编辑、方法/字段增删、try-catch 块编辑、类字节替换、工作区导出。

### 指令操作
`list_method_instructions` `get_method_bytecode` `replace_instruction` `insert_instruction` `remove_instruction`

### 方法体
`assemble_method` `replace_method_body`

### 访问标志
`edit_class_access` `edit_method_access` `edit_field_access`

### 方法/字段 CRUD
`add_method` `remove_method` `add_field` `remove_field`

### 其他
`set_try_catch_blocks` `replace_class_bytes` `save_workspace`

---

## 环境要求

- Java 25
- Python 3.10+
- `recaf.jar`

## 快速开始

### 1. 提供 recaf.jar

构建按以下顺序查找 Recaf：

1. 环境变量 `RECAF_JAR`
2. `libs/recaf.jar`
3. `../recaf/recaf.jar`

```powershell
$env:RECAF_JAR="D:\deobf\recaf\recaf.jar"
```

### 2. 构建插件

```powershell
.\gradlew.bat jar
```

输出：`build/libs/recaf-mcp-plugin-0.1.0.jar`

### 3. 安装插件

将 jar 复制到 Recaf 插件缓存目录 `%APPDATA%/Recaf/plugins/`，启动 Recaf。

加载成功后日志显示：

```
Recaf MCP plugin listening on http://127.0.0.1:8750
```

### 4. 安装 Python 依赖

```powershell
pip install -r requirements.txt
```

### 5. 启动 MCP 服务器

```powershell
python recaf_mcp_server.py --http --port 8751
```

或使用启动脚本：

```powershell
.\start_recaf_mcp_http.ps1
```

---

## MCP 配置

```json
{
  "mcpServers": {
    "recaf-mcp": {
      "url": "http://127.0.0.1:8751/mcp"
    }
  }
}
```

## 端口

| 组件 | 地址 |
|---|---|
| 插件 HTTP | 第一个 Recaf 用 `127.0.0.1:8750`，之后依次取下一个空闲端口，最多到 `8769`，跳过 `8751` |
| MCP HTTP | `127.0.0.1:8751/mcp` |

健康检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8750/health
```

---

## 多 Recaf 窗口

可以同时运行多个 Recaf 进程，各自打开不同的 jar。每个进程都会加载插件，并从 `8750` 起绑定第一个空闲端口；MCP 服务按首次发现的顺序给它们编号。编号在 MCP 服务运行期间不会复用，所以 `2:client.jar` 不会悄悄变成另一个 jar。

| 工具 | 作用 |
|---|---|
| `list_recaf_windows` | 列出所有窗口，形如 `1:client.jar`、`2:server.jar`，并标出默认窗口 |
| `switch_recaf_window(window)` | 把某个窗口设为默认，之后不带 `window` 的调用都走它 |
| `open_recaf_window(jar_path?, wait_seconds?)` | 再开一个 Recaf，可顺带打开一个 jar；不会改动默认窗口 |

其余工具都有可选参数 `window`：填 `"2"`、`"2:client.jar"`，或者 jar 名里能唯一匹配的一段。编号后面带了 jar 名时必须和该窗口当前的 jar 对得上，过期的引用会被拒绝，而不是改错 jar。

- 只开了一个窗口时，可以不传 `window`。
- 开了多个窗口又没选默认窗口时，调用会失败并返回窗口列表，不会猜。
- `switch_recaf_window` 设置的默认窗口由 MCP 服务的所有客户端共享（HTTP 传输没有按客户端区分的会话）。多个 agent 并行时，请各自显式传 `window`。
- `open_recaf_window` 会按已有窗口的启动方式（同一个 `java` 和 jar，从其进程里读取）再开一个。没有任何窗口时，依次用 `--recaf-jar`、`RECAF_JAR`、`libs/recaf.jar`。

仍在用旧版插件（没有 `/instance`）的窗口也能被发现，但不能被 `open_recaf_window` 当作克隆模板。

---

## 仓库结构

```text
.
├── src/                  # Java 插件源码
├── mcp-server/           # Python MCP 服务端
├── libs/                 # recaf.jar 存放处
├── recaf_mcp_server.py   # MCP 服务器入口
├── requirements.txt      # Python 依赖
├── .mcp.json             # 项目 MCP 配置
└── README.md
```

---

## 安全警告

以下方式会暴露 MCP 服务器在公网且无认证：

```powershell
python recaf_mcp_server.py --http --host 0.0.0.0
```

默认使用 localhost，或置于防火墙/VPN 之后。

---

<p align="center">
  <em>LLM 客户端 -> MCP 服务器 -> Recaf 插件 -> Recaf 工作区</em>
</p>
