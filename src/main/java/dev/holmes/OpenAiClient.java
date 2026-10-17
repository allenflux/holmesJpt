package dev.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** 用 JDK HTTP 客户端直接调用兼容 OpenAI 的 Chat Completions 接口。 */
public final class OpenAiClient implements LlmClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final AppConfig config;
    private final HttpClient http;

    public OpenAiClient(AppConfig config) {
        if (config.apiKey().isBlank()) throw new IllegalArgumentException("请先在 .env 文件或系统环境中配置 LLM_API_KEY");
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Reply complete(ArrayNode messages, ArrayNode tools) throws IOException, InterruptedException {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", config.model()).put("stream", false);
        body.set("messages", messages);
        if (!tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        URI endpoint = URI.create(config.baseUrl().toString().replaceAll("/+$", "") + "/chat/completions");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(config.llmTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        for (int attempt = 0; attempt < 3; attempt++) {
            long started = System.nanoTime(), timeoutNanos = config.llmTimeout().toNanos();
            var pending = http.sendAsync(request, info -> new LimitedBody());
            AtomicLong completedAfter = new AtomicLong(-1);
            // 先记录真实完成时刻再唤醒等待方，避免线程晚醒把早已发生的网络错误误判为超时。
            var observed = pending.whenComplete((response, failure) -> completedAfter.set(System.nanoTime() - started));
            HttpResponse<byte[]> response;
            try { response = observed.get(Math.max(1, timeoutNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) {
                pending.cancel(true);
                throw new LlmRequestFailure(LlmRequestFailure.Kind.TIMEOUT);
            } catch (ExecutionException e) {
                // 只辨认可控的本地异常类型，不读取供应商正文或任意异常消息。
                throw requestFailure(e.getCause(), completedAfter.get(), timeoutNanos);
            } catch (InterruptedException e) {
                pending.cancel(true);
                throw e;
            }
            byte[] bytes = response.body();
            // 错误响应不打印原文，避免供应商回显请求或密钥。
            int status = response.statusCode();
            if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < 2) {
                Thread.sleep(250L * (attempt + 1));
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new LlmRequestFailure(LlmRequestFailure.Kind.HTTP, status);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) throw new LlmRequestFailure(LlmRequestFailure.Kind.RESPONSE_LIMIT);
            JsonNode choice;
            try {
                JsonNode decoded = Json.MAPPER.readTree(bytes);
                if (decoded == null || !decoded.isObject()) throw new IOException("响应不是 JSON 对象");
                choice = decoded.path("choices").path(0);
            }
            catch (IOException e) { throw new LlmRequestFailure(LlmRequestFailure.Kind.INVALID_RESPONSE); }
            if (!(choice.path("message") instanceof ObjectNode message)) {
                throw new LlmRequestFailure(LlmRequestFailure.Kind.INVALID_RESPONSE);
            }
            if (!"assistant".equals(message.path("role").asText())) {
                throw new LlmRequestFailure(LlmRequestFailure.Kind.INVALID_RESPONSE);
            }
            return new Reply(message.deepCopy(), choice.path("finish_reason").asText("stop"));
        }
        throw new LlmRequestFailure(LlmRequestFailure.Kind.NETWORK);
    }

    /** 异步异常可以被多层包装；只检查类型和明确截止时间，不解析消息或保存异常链。 */
    static LlmRequestFailure requestFailure(Throwable cause, long completedAfter, long timeoutNanos) {
        boolean timeout = false;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof LlmRequestFailure failure) return new LlmRequestFailure(failure.kind(), failure.httpStatus());
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException || cause instanceof TimeoutException)
                timeout = true;
        }
        return new LlmRequestFailure(timeout || completedAfter >= timeoutNanos ? LlmRequestFailure.Kind.TIMEOUT : LlmRequestFailure.Kind.NETWORK);
    }

    /** 边接收边限制内存，并让总超时覆盖响应体，而不只等待响应头。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new LlmRequestFailure(LlmRequestFailure.Kind.RESPONSE_LIMIT));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
