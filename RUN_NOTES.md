# 运行记录

> 历史记录：下文记录的是一次早期构建与首次启动现场，不代表当前版本的功能状态。当前安装和使用方法以 [README.md](README.md) 为准。

本文档记录了对 SimpleRAG 项目的构建与运行操作。注意：**当时未修改任何源代码**，仅执行了构建和启动。

## 环境确认

| 项目 | 版本 | 状态 |
| --- | --- | --- |
| JDK | 21.0.11 (Oracle),`JAVA_HOME=D:\java_jdk` | 满足要求(需 17+) |
| Maven | 3.9.6 | 满足要求(需 3.9+) |
| 操作系统 | Windows 11 | OK |

## 构建步骤

跳过测试打包(测试需要本地 ONNX 模型,尚未安装):

```bash
mvn -q -DskipTests package
```

结果:构建成功,生成两个 JAR:

- `target/SimpleRAG-1.0-SNAPSHOT.jar` — 约 17 MB,已包含所有依赖(SQLite、Jackson、FlatLaf 等),可直接运行。
- `target/original-SimpleRAG-1.0-SNAPSHOT.jar` — 约 114 KB,未打包依赖的原始 JAR。

## 启动步骤

```bash
"$JAVA_HOME/bin/java.exe" -jar target/SimpleRAG-1.0-SNAPSHOT.jar
```

结果:Swing 桌面 GUI 客户端正常启动,无错误输出,Java 进程稳定运行。

## 当时尚未完成

1. **语义模型未安装。** 语义检索功能需要本地 ONNX 模型。根据 README,需运行:

   ```powershell
   .\setup-semantic-model.cmd
   ```

   该脚本会从 `https://hf-mirror.com` 下载约 122 MB 的模型到 `D:\SimpleRAG\models\multilingual-minilm`。
   未安装时,GUI 可以打开,但底部状态不会显示"向量语义已启用",向量检索无法工作。

2. **测试未运行。** 因为 `SemanticSearchEngineTest` 依赖真实 ONNX 模型。安装模型后可运行完整测试:

   ```powershell
   .\build-and-test.cmd
   ```

## 数据存储位置(供参考)

| 数据 | 位置 |
| --- | --- |
| ONNX 模型 | `D:\SimpleRAG\models\multilingual-minilm` |
| SQLite 数据库 | `%USERPROFILE%\.simplerag\simplerag.db` |
| 每知识库索引 | `%USERPROFILE%\.simplerag\indexes\<知识库ID>\<revision>.bin` |

## 后续更新

> 以上为首次运行时的记录。此后架构有变更，以下信息已更新：

- **已去除 Python 桥接**，改用 LangChain4j 的 in-process ONNX 组件（详见 DEVELOPMENT.md 5.9）。运行时不再需要 Python、NumPy 或 pip 包。
- **JAR 体积变化**：因打包 onnxruntime（含全平台原生库）和 DJL tokenizer，自包含 JAR 从约 17 MB 增至约 130 MB。
- **模型下载改为纯 Java**：`setup-semantic-model.cmd` 现调用 `com.simplerag.adapter.out.onnx.ModelDownloader`，不再依赖 Python 脚本。
- 2026-09-11 起，本地语义模型可直接在“设置 → 本地语义模型”中下载；`setup-semantic-model.cmd` 仅作为命令行替代入口。
- 启动过程现在显示数据库、配置和索引恢复阶段；启动失败会显示具体错误。
- 问答对话现已持久化到 SQLite，应用退出后可以恢复。
- 不再产生 `~/.simplerag/embedding.log` 进程日志。
