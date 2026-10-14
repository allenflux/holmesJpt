package dev.holmes;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/** 引擎只依赖消息协议，便于替换真实模型或离线演示模型。 */
@FunctionalInterface
public interface LlmClient {
    Reply complete(ArrayNode messages, ArrayNode tools) throws IOException, InterruptedException;
    record Reply(ObjectNode message, String finishReason) { }
}
