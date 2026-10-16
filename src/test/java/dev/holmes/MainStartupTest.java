package dev.holmes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 所有配置均由测试生成；不读取项目真实 .env 或实际系统环境。 */
class MainStartupTest {
    @TempDir Path directory;

    @Test void helpNeverReadsSelectedFileOrValidatesEnvironment() throws Exception {
        Path absent = directory.resolve("missing-private-marker.env");
        Path malformed = directory.resolve("malformed.env"); Files.writeString(malformed, "private-secret invalid syntax");
        for (String[] args : List.of(new String[0], new String[]{"--help"},
                new String[]{"--env-file", absent.toString(), "--help"},
                new String[]{"web", "--env-file", malformed.toString(), "--help"},
                new String[]{"logs", "--help", "--env-file", absent.toString()})) {
            String output = output(() -> Main.run(args, Map.of("HOLMES_MAX_STEPS", "invalid")));
            assertTrue(output.contains("--env-file"));
            assertFalse(output.contains("private-secret"));
        }
    }

    @Test void explicitMissingOrUnreadableFileCannotFallBackAndErrorsAreSanitized() throws Exception {
        for (Path file : List.of(directory.resolve("missing-private-marker.env"), directory)) {
            IOException error = assertThrows(IOException.class,
                    () -> Main.run(new String[]{"tools", "--env-file", file.toString()}, Map.of()));
            assertTrue(error.getMessage().contains("--env-file"));
            assertFalse(error.toString().contains("private-marker"));
            assertFalse(error.toString().contains(directory.toString()));
            assertNull(error.getCause());
        }
    }

    @Test void realToolsCommandUsesFileThenSystemThenCliPrecedenceWithoutPrintingSecrets() throws Exception {
        Path envTools = tools("file-tools.json", "file_tool");
        Path systemTools = tools("system-tools.json", "system_tool");
        Path cliTools = tools("cli tools.json", "cli_tool");
        Path env = directory.resolve("selected config.env");
        Files.writeString(env, "HOLMES_TOOLS_FILE='" + envTools + "'\nLLM_API_KEY=synthetic-private-key\n");
        var system = Map.of("HOLMES_TOOLS_FILE", systemTools.toString());

        String fromFile = output(() -> Main.run(new String[]{"--env-file", env.toString(), "tools"}, Map.of()));
        String fromSystem = output(() -> Main.run(new String[]{"tools", "--env-file", env.toString()}, system));
        String fromCli = output(() -> Main.run(new String[]{"tools", "--tools", cliTools.toString(), "--env-file", env.toString()}, system));

        assertTrue(fromFile.contains("file_tool")); assertFalse(fromFile.contains("system_tool"));
        assertTrue(fromSystem.contains("system_tool")); assertFalse(fromSystem.contains("file_tool"));
        assertTrue(fromCli.contains("cli_tool")); assertFalse(fromCli.contains("system_tool"));
        for (String value : List.of(fromFile, fromSystem, fromCli)) assertFalse(value.contains("synthetic-private-key"));
    }

    private Path tools(String filename, String name) throws IOException {
        var config = Json.MAPPER.createObjectNode().put("fileRoot", directory.toString());
        var command = config.putArray("commands").addObject().put("name", name).put("description", "synthetic tool");
        command.putArray("argv").add("/usr/bin/true");
        var schema = command.putObject("parameters").put("type", "object"); schema.putObject("properties"); schema.putArray("required");
        Path file = directory.resolve(filename); Files.writeString(file, config.toString()); return file;
    }

    private static String output(Action action) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture); action.run();
        } finally { System.setOut(old); }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
