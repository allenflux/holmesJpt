package dev.holmes;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 全项目共用同一个 JSON 编解码器。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() { }
}
