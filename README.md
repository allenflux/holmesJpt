# Holmes Java 精简版

用 Java 27 重写 [allenflux/holmesgpt](https://github.com/allenflux/holmesgpt) 的核心调查循环：用户提问 → 大模型选择工具 → Java 执行工具 → 工具证据返回模型 → 输出调查结论。

详细中文文档与两张 Diagram Design 图已发布到 [Confluence：Holmes Java 运行逻辑、架构与开发指南](https://fluxa0223.atlassian.net/wiki/spaces/SD/pages/1474561/Holmes+Java)。

这是便于理解、运行和扩展的功能子集，提供 Holmes 网页、日志分析、OpenAI 兼容模型调用、文件证据、管理员配置的命令工具、Markdown 操作手册和 CLI。网页可先在本地调试，再通过 Docker Compose 部署。运行时只依赖 Jackson，不需要 Spring、数据库或向量库。代码中的注释使用中文。

## 快速启动

需要 JDK 27 和 Maven 3.9+。在本机 macOS 上选择已通过 Homebrew 安装的 JDK 27：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
```

确认两个版本命令都显示 Java 27 后再构建。IntelliJ IDEA 的 Project SDK 和 Maven Runner JRE 也都选择 JDK 27；终端中的 `JAVA_HOME` 不会自动替换 IDEA 已保存的 SDK 设置。

```bash
mvn package
java -jar target/holmes-java-0.1.0.jar web --demo --port 8080
```

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，首版默认无密码，界面以英文为主并支持中文切换。`web --demo` 使用合成任务日志与脚本问答，不需要模型 Key；真实来源和远端部署见 [网页服务与部署](docs/网页服务与部署.md)。原来的 `demo` 命令仍可在终端验证调查循环。

调用真实模型时，在项目根目录的 `.env` 文件中填写以下配置（已有文件直接编辑）：

```dotenv
LLM_BASE_URL=https://llm.aiaipool.com/v1
LLM_MODEL=deepseek-v41-flash
LLM_API_KEY=你的APIKey
```

然后在项目根目录直接运行：

```bash
java -jar target/holmes-java-0.1.0.jar ask 'checkout 服务为什么支付失败？' --trace
```

程序启动时自动读取当前工作目录的 `.env` 文件，`web`、`ask`、`serve`、`logs` 共用此行为。也可用 `--env-file /绝对路径/.env` 明确指定配置文件，放在子命令前后均可；显式指定的文件不存在或不可读时会报错。配置优先级为命令行参数 > 系统环境变量 > 所选 `.env` > 默认值；未指定文件且当前目录没有 `.env` 时仍可使用环境变量。IDE 的 Working directory 应设为项目根目录，修改配置后重启进程。`.env` 已被 Git 忽略，不要将真实 Key 写入仓库或文档。

启动网页时必须带 `web` 子命令；只运行 JAR 会显示帮助后退出。例如在项目根目录运行：

```bash
java -jar target/holmes-java-0.1.0.jar web --env-file /path/to/holmes/.env --port 8080
```

`--env-file` 仅选择配置文件，不改变工作目录；配置内的相对文件路径仍按启动目录解析，IDE 中也需要设置 Working directory。

## 常用命令

```bash
# 查看本次配置中开放的工具
java -jar target/holmes-java-0.1.0.jar tools

# 指定工具配置、手册目录与调查步数
java -jar target/holmes-java-0.1.0.jar ask '分析 checkout 支付失败的原因' \
  --tools config/tools.json --runbooks runbooks --max-steps 8 --trace

# 启动本地 HTTP 服务
java -jar target/holmes-java-0.1.0.jar serve --port 8080

# 另一终端发起调查
curl -s http://127.0.0.1:8080/health
curl -s http://127.0.0.1:8080/api/investigate \
  -H 'Content-Type: application/json' \
  -d '{"question":"checkout 服务为什么支付失败？"}'
```

服务绑定 `127.0.0.1`。真实调查需要已配置 `LLM_API_KEY`，工具命令在运行 Java 的机器上执行。

## 阅读顺序

1. [运行逻辑](docs/运行逻辑.md)：从请求到结论、消息协议、调查循环、停止条件与支付配置/OOM 两个独立示例。
2. [内部机制详解](docs/内部机制详解.md)：跟着三轮支付故障调查，逐条看请求 JSON、消息增长、工具执行、缓存、预算和 HTTP 生命周期；含六张细节图与源码断点位置。
3. [配置与扩展](docs/配置与扩展.md)：环境变量、工具配置、手册、HTTP 和常见故障。
4. [原项目对照](docs/原项目对照.md)：原版设计、Java 对应关系和精简范围。
5. [OOM 调查手册](runbooks/oom.md)：可以直接进入系统提示的调查方法。

Java 源码仓库为 [allenflux/holmesJpt](https://github.com/allenflux/holmesJpt)。详解中的源码引用固定到 `2f5b88fa89d3e71ba5b040f9498cbe62dab11f0d`，可直接核对对应方法和行号。

图表使用 Diagram Design 的默认浅色风格：打开 [架构图](docs/diagrams/architecture.html) 和 [调查流程图](docs/diagrams/investigation-flow.html)。详细正文的富文本版本为 [Confluence 正文](docs/confluence.html)。

## 能力边界

当前版本专注于一次请求内的证据调查，不提供原项目的多模型适配层、MCP、云监控集成、Kubernetes Operator、告警平台回写、持久化历史、流式输出或历史压缩。它不会自动连接生产集群；只有在管理员配置对应命令后，相关证据才可被模型使用。调查结论是证据辅助判断，缺少证据时应明确说明未知项。

## 独立日志首版

`logs analyze` / `logs serve` 默认通过线上统计 GET API 读取失败任务，关联 Grafana / VictoriaLogs 所有 Pod 的 task_id / prompt_id 日志，并按需将大日志归档到 FluxDrop。无需人工准备任务快照；显式设置 `HOLMES_LOG_TASKS_FILE` 或 `--tasks` 时优先使用文件。此模式无需模型 Key，提供异步批次 API、有界本地检查点和固定离线样本；生产 Grafana 读取权限仍需验证。

线上按 `created_at` 筛选请求窗口，`status=-1` 表示读取时失败，`updated_at` 不是失败时间；动态分页与预算截断不保证取全。缺少 prompt、attempt 或重试状态时，报告保留候选证据和 `observed_signatures`，主簇标为 `INCOMPLETE`。`observed_clusters` 是可重叠候选组，不能与主 `clusters` 混作最终失败根因。

运行步骤、样本预期、API、归档和容量边界见 [日志首版使用指南](docs/日志首版使用指南.md)。

## 网页本地调试与部署

在项目根目录启动包含合成日志和脚本问答的演示页面，默认不需要密码：

```bash
mvn package
java -jar target/holmes-java-0.1.0.jar web --demo --port 8080
```

浏览器打开 [http://127.0.0.1:8080](http://127.0.0.1:8080) 直接进入页面。`HOLMES_WEB_PASSWORD` 缺失或留空时不启用登录；以后需要时可在已有 `.env` 配置 12–256 个字符的密码，重启后启用。去掉 `--demo` 后默认自动读取线上失败任务，`HOLMES_LOG_TASKS_FILE` 留空即可；日志来自实际 Grafana 数据源。配置 `LLM_API_KEY` 后启用通用问答。Docker Compose、同站 HTTPS 代理及远端配置步骤见 [网页服务与部署](docs/网页服务与部署.md)。

网页名称为 **Holmes**，默认 English，支持切换中文。Bootstrap CSS 与 JavaScript 直接使用官方 `cdn.jsdelivr.net` 的固定 `5.3.8` 版本，并校验 SRI；运行浏览器需要能访问该 CDN。应用自己的 CSS 与 JavaScript 仍由本地服务提供，密钥只在服务端配置。`localStorage` 仅保存语言偏好，`sessionStorage` 仅保存当前批次 ID，不存储密码或 API Key。
