package dev.holmes.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @TempDir Path temporary;

    @Test
    void fileToolsResolveRelativeRootAndSearchWithLineNumbers() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("事件.log"), "启动成功\nPayment connection refused\n");
        Files.write(evidence.resolve("binary.bin"), new byte[]{0, 1, 2});
        ToolRegistry registry = registry(List.of(), 2, 4_000);

        ToolResult listing = registry.execute("list_files", json("{}"));
        assertTrue(listing.success());
        assertTrue(listing.output().contains("事件.log"));
        assertFalse(listing.output().contains("binary.bin"));
        assertEquals("启动成功\nPayment connection refused\n",
                registry.execute("read_file", json("{\"path\":\"事件.log\"}")).output());
        ToolResult match = registry.execute("search_files", json("{\"query\":\"PAYMENT\"}"));
        assertTrue(match.success());
        assertEquals("事件.log:2: Payment connection refused\n", match.output());
    }

    @Test
    void refusesTraversalAbsolutePathsAndSymlinkEscapes() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Path outside = Files.writeString(temporary.resolve("secret.txt"), "private marker");
        Files.createSymbolicLink(evidence.resolve("link.txt"), outside);
        Files.createSymbolicLink(evidence.resolve("directory-link"), temporary);
        ToolRegistry registry = registry(List.of(), 2, 4_000);

        for (String path : List.of("../secret.txt", "nested/../../secret.txt", outside.toString(),
                "link.txt", "directory-link/secret.txt")) {
            ObjectNode argument = Json.MAPPER.createObjectNode().put("path", path);
            ToolResult result = registry.execute("read_file", argument);
            assertFalse(result.success(), path);
            assertFalse(result.output().contains("private marker"), path);
        }
        assertFalse(registry.execute("list_files", json("{}")).output().contains("link.txt"));
        assertFalse(registry.execute("read_file", json("{\"path\":\".\"}")).success());
    }

    @Test
    void enforcesReadOutputAndScanningLimits() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("large.log"), "证据".repeat(150_000));
        ToolRegistry registry = registry(List.of(), 2, 80);
        ToolResult result = registry.execute("read_file", json("{\"path\":\"large.log\"}"));
        assertTrue(result.success());
        assertTrue(result.truncated());
        assertTrue(result.output().length() <= 80);

        for (int index = 0; index < 2_020; index++) Files.writeString(evidence.resolve("entry-" + index + ".txt"), "x");
        assertTrue(registry.execute("list_files", json("{}")).truncated());
    }

    @Test
    void rejectsInvalidUtf8AndUnexpectedBuiltinArguments() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.write(evidence.resolve("invalid.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});
        ToolRegistry registry = registry(List.of(), 2, 4_000);
        assertFalse(registry.execute("read_file", json("{\"path\":\"invalid.txt\"}")).success());
        assertFalse(registry.execute("read_file", json("{}")).success());
        assertFalse(registry.execute("list_files", json("{\"path\":\"invalid.txt\"}")).success());
        assertFalse(registry.execute("search_files", json("{\"query\":\"\"}")).success());
        assertFalse(registry.execute("read_file", json("[]")).success());
        assertFalse(registry.execute("unknown", json("{}")).success());
        assertFalse(registry.execute(null, json("{}")).success());
    }

    @Test
    void binaryFilesAlsoConsumeTheSharedScanReadBudget() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        byte[] binary = new byte[256 * 1_024];
        for (int index = 0; index < 40; index++) {
            Files.write(evidence.resolve("binary-%02d.bin".formatted(index)), binary);
        }
        Files.writeString(evidence.resolve("z-remaining.txt"), "find-this-marker");
        ToolRegistry registry = registry(List.of(), 2, 4_000);
        ToolResult listing = registry.execute("list_files", json("{}"));
        assertTrue(listing.success());
        assertTrue(listing.truncated());
        assertFalse(listing.output().contains("z-remaining.txt"));
        ToolResult search = registry.execute("search_files", json("{\"query\":\"find-this-marker\"}"));
        assertTrue(search.success());
        assertTrue(search.truncated());
        assertFalse(search.output().contains("z-remaining.txt"));
    }

    @Test
    void validatesCommandParametersAndNeverEvaluatesShellText() throws Exception {
        Files.createDirectory(temporary.resolve("evidence"));
        ObjectNode command = command("echo_value", List.of("/usr/bin/printf", "%s", "{value}"));
        command.set("parameters", json("""
                {"type":"object","properties":{"value":{"type":"string","pattern":"[^\\r\\n]{1,512}","maxLength":512}},"required":["value"]}
                """));
        ToolRegistry registry = registry(List.of(command), 2, 4_000);
        Path marker = temporary.resolve("must-not-exist");
        String payload = "$(touch " + marker + "); echo test";
        ToolResult output = registry.execute("echo_value", Json.MAPPER.createObjectNode().put("value", payload));
        assertTrue(output.success());
        assertEquals(payload, output.output());
        assertFalse(Files.exists(marker));

        for (String arguments : List.of("{}", "{\"value\":\"--help\"}", "{\"value\":7}",
                "{\"value\":\"ok\",\"extra\":\"bad\"}", "{\"value\":\"line\\nline\"}")) {
            assertFalse(registry.execute("echo_value", json(arguments)).success(), arguments);
        }
        // 调用方拿到的定义副本不能修改内部校验规则。
        registry.definitions().removeAll();
        assertEquals(4, registry.definitions().size());
    }

    @Test
    void rejectsUnboundedOrUnboundCommandConfiguration() throws Exception {
        Files.createDirectory(temporary.resolve("evidence"));
        ObjectNode missingPattern = command("invalid", List.of("/usr/bin/printf", "%s", "{value}"));
        missingPattern.set("parameters", json("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}"));
        assertThrows(java.io.IOException.class, () -> registry(List.of(missingPattern), 2, 4_000));
        ObjectNode unbound = command("invalid", List.of("/usr/bin/printf", "%s", "{value}"));
        assertThrows(java.io.IOException.class, () -> registry(List.of(unbound), 2, 4_000));
        ObjectNode duplicate = command("list_files", List.of("/usr/bin/true"));
        assertThrows(java.io.IOException.class, () -> registry(List.of(duplicate), 2, 4_000));
    }

    @Test
    void drainsLargeCommandOutputWithoutBlockingAndReportsExitStatus() throws Exception {
        Files.createDirectory(temporary.resolve("evidence"));
        ToolRegistry registry = registry(List.of(
                command("flood", List.of("/bin/sh", "-c", "i=0; while [ $i -lt 20000 ]; do printf 'abcdefghij'; i=$((i+1)); done")),
                command("failure", List.of("/bin/sh", "-c", "printf 'diagnostic'; exit 7"))), 5, 100);
        ToolResult flood = assertTimeoutPreemptively(Duration.ofSeconds(6),
                () -> registry.execute("flood", json("{}")));
        assertTrue(flood.success(), flood.output());
        assertTrue(flood.truncated());
        assertEquals(100, flood.output().length());
        ToolResult failure = registry.execute("failure", json("{}"));
        assertFalse(failure.success());
        assertTrue(failure.output().contains("7"));
        assertTrue(failure.output().contains("diagnostic"));
    }

    @Test
    void timeoutTerminatesChildProcessAsWellAsParent() throws Exception {
        Files.createDirectory(temporary.resolve("evidence"));
        ToolRegistry registry = registry(List.of(command("wait_tree", List.of("/bin/sh", "-c",
                "sleep 30 & child=$!; trap 'wait; exit' TERM; printf '%s\\n' $child; wait"))), 1, 1_000);
        ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> registry.execute("wait_tree", json("{}")));
        assertFalse(result.success());
        assertTrue(result.output().contains("超时"), result.output());
        var pid = Pattern.compile("(?m)^([0-9]+)$").matcher(result.output());
        assertTrue(pid.find(), "测试命令应在超时前输出子进程 PID");
        long childPid = Long.parseLong(pid.group(1));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20);
        });
    }

    @Test
    void removesModelAndServerSecretsFromChildEnvironment() throws Exception {
        Files.createDirectory(temporary.resolve("evidence"));
        registry(List.of(command("environment", List.of("/usr/bin/env"))), 2, 100_000);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                EnvironmentProbe.class.getName(), temporary.resolve("tools.json").toString());
        builder.environment().put("LLM_API_KEY", "test-private-marker-model");
        builder.environment().put("HOLMES_SERVER_API_KEY", "test-private-marker-server");
        builder.environment().put("OPENAI_API_KEY", "test-private-marker-openai");
        builder.environment().put("TOOL_TEST_ALLOWED", "preserved");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), errors);
        assertEquals("true:false:true", output);
    }

    private ToolRegistry registry(List<ObjectNode> commands, int timeout, int limit) throws Exception {
        ObjectNode config = Json.MAPPER.createObjectNode().put("fileRoot", "evidence");
        config.set("commands", Json.MAPPER.valueToTree(commands));
        Path path = temporary.resolve("tools.json");
        Files.writeString(path, Json.MAPPER.writeValueAsString(config));
        return ToolRegistry.load(path, timeout, limit);
    }

    private static ObjectNode command(String name, List<String> argv) throws Exception {
        ObjectNode command = Json.MAPPER.createObjectNode().put("name", name).put("description", "测试命令");
        command.set("parameters", json("{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        command.set("argv", Json.MAPPER.valueToTree(argv));
        return command;
    }

    private static JsonNode json(String value) throws Exception { return Json.MAPPER.readTree(value); }

    /** 独立 JVM 接收测试密钥，证明真正启动的工具子进程不会继承它们。 */
    public static final class EnvironmentProbe {
        public static void main(String[] args) throws Exception {
            ToolResult result = ToolRegistry.load(Path.of(args[0]), 2, 100_000)
                    .execute("environment", Json.MAPPER.createObjectNode());
            System.out.print(result.success() + ":" + result.output().contains("test-private-marker")
                    + ":" + result.output().contains("TOOL_TEST_ALLOWED=preserved"));
        }
    }
}
