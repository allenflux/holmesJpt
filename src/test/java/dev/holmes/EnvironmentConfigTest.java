package dev.holmes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 只使用临时合成配置；不读取项目真实 .env 或进程中的密钥。 */
class EnvironmentConfigTest {
    @TempDir Path directory;

    @Test void missingExplicitFileKeepsSystemEnvironmentAndDoesNotSearchParents() throws Exception {
        write(directory.resolve(".env"), "PARENT_ONLY=synthetic-parent-value\n");
        Path absent = directory.resolve("nested").resolve(".env");
        Map<String, String> system = new LinkedHashMap<>();
        system.put("SYSTEM_ONLY", "synthetic-system-value");
        system.put("EMPTY", "");

        var loaded = EnvironmentConfig.load(absent, system);

        assertEquals(system, loaded);
        assertFalse(loaded.containsKey("PARENT_ONLY"));
        assertEquals("", loaded.get("EMPTY"));
        assertEquals(Map.of("SYSTEM_ONLY", "synthetic-system-value", "EMPTY", ""), system);
    }

    @Test void utf8BomCrLfCommentsExportAndAssignmentWhitespaceAreAccepted() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), "\uFEFF# 合成配置\r\n\r\n"
                + "   # 注释\r\n export EXPORTED=导出值\r\n UNDER_SCORE_2 = value with spaces  \r\nEMPTY=\r\n");

        var loaded = EnvironmentConfig.load(file, Map.of());

        assertEquals(Map.of("EXPORTED", "导出值", "UNDER_SCORE_2", "value with spaces", "EMPTY", ""), loaded);
    }

    @Test void singleQuotesAreLiteralAndExpansionOrCommandsAreNeverEvaluated() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), String.join("\n",
                "SINGLE='${REPLACEMENT} \\n \\t \\\\ # literal = value'",
                "VARIABLE=$REPLACEMENT/${REPLACEMENT}",
                "COMMAND=$(printf synthetic-command)",
                "BACKTICKS=`printf synthetic-command`", ""));

        var loaded = EnvironmentConfig.load(file, Map.of("REPLACEMENT", "must-not-expand"));

        assertEquals("${REPLACEMENT} \\n \\t \\\\ # literal = value", loaded.get("SINGLE"));
        assertEquals("$REPLACEMENT/${REPLACEMENT}", loaded.get("VARIABLE"));
        assertEquals("$(printf synthetic-command)", loaded.get("COMMAND"));
        assertEquals("`printf synthetic-command`", loaded.get("BACKTICKS"));
    }

    @Test void doubleQuotedSupportedEscapesPreserveWhitespaceAndLiteralHashes() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), "DOUBLE=\"  line1\\nline2\\r\\t\\\"quote\\\"\\\\tail # = ${OTHER}  \" # comment\n");

        var loaded = EnvironmentConfig.load(file, Map.of("OTHER", "must-not-expand"));

        assertEquals("  line1\nline2\r\t\"quote\"\\tail # = ${OTHER}  ", loaded.get("DOUBLE"));
    }

    @Test void unquotedCommentsRequireLeadingWhitespaceAndEqualsRemainInValue() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), String.join("\n",
                "COMMENT=value # trailing comment",
                "TAB_COMMENT=value\t# trailing comment",
                "HASH=value#literal",
                "INITIAL_HASH=#literal",
                "EMPTY_COMMENT= # trailing comment",
                "EQUALS=a=b=c#literal",
                "URL=https://example.invalid/path?a=b#fragment", ""));

        var loaded = EnvironmentConfig.load(file, Map.of());

        assertEquals("value", loaded.get("COMMENT"));
        assertEquals("value", loaded.get("TAB_COMMENT"));
        assertEquals("value#literal", loaded.get("HASH"));
        assertEquals("#literal", loaded.get("INITIAL_HASH"));
        assertEquals("", loaded.get("EMPTY_COMMENT"));
        assertEquals("a=b=c#literal", loaded.get("EQUALS"));
        assertEquals("https://example.invalid/path?a=b#fragment", loaded.get("URL"));
    }

    @Test void quotedValuesAllowOnlyWhitespaceAndCommentsAfterClosingQuote() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), String.join("\n",
                "SINGLE='literal # value'   # trailing comment",
                "DOUBLE=\"literal # value\"# trailing comment",
                "EMPTY_SINGLE=''",
                "EMPTY_DOUBLE=\"\"", ""));

        var loaded = EnvironmentConfig.load(file, Map.of());

        assertEquals("literal # value", loaded.get("SINGLE"));
        assertEquals("literal # value", loaded.get("DOUBLE"));
        assertEquals("", loaded.get("EMPTY_SINGLE"));
        assertEquals("", loaded.get("EMPTY_DOUBLE"));
    }

    @Test void lastFileDefinitionWinsAndSystemEnvironmentOverridesEvenWithEmptyValue() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), String.join("\n",
                "REPEATED=first", "REPEATED=last", "OVERRIDDEN=file-value", "EMPTY_OVERRIDE=file-value", ""));
        Map<String, String> system = new LinkedHashMap<>(Map.of("OVERRIDDEN", "system-value", "EMPTY_OVERRIDE", "", "SYSTEM_ONLY", "present"));

        var loaded = EnvironmentConfig.load(file, system);

        assertEquals("last", loaded.get("REPEATED"));
        assertEquals("system-value", loaded.get("OVERRIDDEN"));
        assertEquals("", loaded.get("EMPTY_OVERRIDE"));
        assertEquals("present", loaded.get("SYSTEM_ONLY"));
        assertFalse(system.containsKey("REPEATED"));
    }

    @Test void syntaxErrorsIdentifyOnlyLineNumberWithoutEchoingSecretsOrCause() throws Exception {
        for (String malformed : List.of("NO_EQUALS_private-secret", "1INVALID_private-secret=value",
                "LLM_API_KEY='private-secret", "LLM_API_KEY=\"private-secret",
                "LLM_API_KEY='private-secret' unexpected", "LLM_API_KEY=\"private-secret\" unexpected", "=private-secret")) {
            Path file = write(directory.resolve("synthetic.env"), "# first line\nVALID=synthetic\n" + malformed + "\n");

            IOException error = assertThrows(IOException.class, () -> EnvironmentConfig.load(file, Map.of()));

            assertTrue(error.getMessage().contains("3"));
            assertFalse(error.toString().contains("private-secret"));
            assertFalse(error.toString().contains("LLM_API_KEY"));
            assertFalse(error.toString().contains("unexpected"));
            assertNull(error.getCause());
        }
    }

    @Test void fileSizeLimitIsExactly64KiBAndMeasuredAsUtf8Bytes() throws Exception {
        Path exact = write(directory.resolve("exact.env"), "BIG=" + "a".repeat(65_532));
        assertEquals(65_536, Files.size(exact));
        assertEquals(65_532, EnvironmentConfig.load(exact, Map.of()).get("BIG").length());

        Path tooLarge = write(directory.resolve("large.env"), "BIG=" + "a".repeat(65_533));
        assertThrows(IOException.class, () -> EnvironmentConfig.load(tooLarge, Map.of()));
        String multibyte = "BIG=" + "界".repeat(22_000);
        assertTrue(multibyte.length() < 65_536);
        Path unicode = write(directory.resolve("unicode.env"), multibyte);
        assertTrue(Files.size(unicode) > 65_536);
        assertThrows(IOException.class, () -> EnvironmentConfig.load(unicode, Map.of()));
    }

    @Test void loadedSyntheticFileConfiguresAppAndExplicitEnvironmentStillWins() throws Exception {
        Path file = write(directory.resolve("synthetic.env"), String.join("\n",
                "LLM_BASE_URL=https://synthetic-provider.example/v1",
                "LLM_API_KEY='synthetic-api-key'",
                "LLM_MODEL=file-model",
                "LLM_TIMEOUT_SECONDS=45",
                "HOLMES_MAX_STEPS=12",
                "HOLMES_PORT=9090", ""));

        var config = AppConfig.from(EnvironmentConfig.load(file, Map.of("LLM_MODEL", "system-model")));

        assertEquals("https://synthetic-provider.example/v1", config.baseUrl().toString());
        assertEquals("synthetic-api-key", config.apiKey());
        assertEquals("system-model", config.model());
        assertEquals(Duration.ofSeconds(45), config.llmTimeout());
        assertEquals(12, config.maxSteps());
        assertEquals(9090, config.port());
        assertFalse(config.toString().contains("synthetic-api-key"));
    }

    private static Path write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
