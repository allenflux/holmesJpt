package dev.holmes.tools;

/** 工具执行结果；截断标记提醒模型证据可能不完整。 */
public record ToolResult(boolean success, String output, boolean truncated) { }
