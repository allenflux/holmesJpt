# Holmes Java 精简版

用 Java 27 重写 [allenflux/holmesgpt](https://github.com/allenflux/holmesgpt) 的核心调查循环：用户提问 → 大模型选择工具 → Java 执行工具 → 工具证据返回模型 → 输出调查结论。

详细中文文档与两张 Diagram Design 图已发布到 [Confluence：Holmes Java 运行逻辑、架构与开发指南](https://fluxa0223.atlassian.net/wiki/spaces/SD/pages/1474561/Holmes+Java)。

这是便于理解、运行和扩展的功能子集，保留 OpenAI 兼容模型调用、文件证据、管理员配置的命令工具、Markdown 操作手册、CLI 和本地 HTTP 接口。运行时只依赖 Jackson，不需要 Spring、数据库或向量库。代码中的注释使用中文。

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
mvn test
mvn package
java -jar target/holmes-java-0.1.0.jar demo
```

`demo` 使用离线脚本模型，执行真实的文件工具，读取 `examples/evidence` 中的示例证据；它用于验证调查循环，不需要 API Key，也不代表真实模型效果。

调用真实模型时，在启动进程的环境中设置：

```bash
export LLM_BASE_URL='https://llm.aiaipool.com/v1'
export LLM_MODEL='deepseek-v41-flash'
read -s 'LLM_API_KEY?请输入 API Key: '
export LLM_API_KEY
java -jar target/holmes-java-0.1.0.jar ask 'checkout 服务为什么支付失败？' --trace
```

上面的 `read -s` 示例适用于 macOS 默认的 zsh。不要把真实 Key 写入仓库、文档或命令参数。程序直接读取环境变量；不会自动加载 `.env` 文件。

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
2. [配置与扩展](docs/配置与扩展.md)：环境变量、工具配置、手册、HTTP 和常见故障。
3. [原项目对照](docs/原项目对照.md)：原版设计、Java 对应关系和精简范围。
4. [OOM 调查手册](runbooks/oom.md)：可以直接进入系统提示的调查方法。

图表使用 Diagram Design 的默认浅色风格：打开 [架构图](docs/diagrams/architecture.html) 和 [调查流程图](docs/diagrams/investigation-flow.html)。详细正文的富文本版本为 [Confluence 正文](docs/confluence.html)。

## 能力边界

当前版本专注于一次请求内的证据调查，不提供原项目的多模型适配层、MCP、云监控集成、Kubernetes Operator、告警平台回写、持久化历史、流式输出或历史压缩。它不会自动连接生产集群；只有在管理员配置对应命令后，相关证据才可被模型使用。调查结论是证据辅助判断，缺少证据时应明确说明未知项。
