package dev.holmes;

import java.io.IOException;
import java.util.Objects;

/** 仅保留可公开的供应商失败类型和 HTTP 状态，不携带响应正文、请求或异常链。 */
public final class LlmRequestFailure extends IOException {
    public enum Kind { HTTP, TIMEOUT, NETWORK, RESPONSE_LIMIT, INVALID_RESPONSE }

    private final Kind kind;
    private final int httpStatus;

    public LlmRequestFailure(Kind kind) { this(kind, 0); }

    public LlmRequestFailure(Kind kind, int httpStatus) {
        super(message(Objects.requireNonNull(kind), httpStatus));
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() { return kind; }
    public int httpStatus() { return httpStatus; }

    private static String message(Kind kind, int status) {
        if (kind == Kind.HTTP ? status < 100 || status > 599 : status != 0)
            throw new IllegalArgumentException("供应商失败状态无效");
        return switch (kind) {
            case HTTP -> "LLM HTTP " + status + "；请检查地址、密钥、模型或供应商限流状态";
            case TIMEOUT -> "LLM 请求超时（包含响应体读取）";
            case NETWORK -> "LLM 网络请求失败";
            case RESPONSE_LIMIT -> "LLM 响应超过 2 MiB 限制";
            case INVALID_RESPONSE -> "LLM 返回了无效的响应格式";
        };
    }
}
