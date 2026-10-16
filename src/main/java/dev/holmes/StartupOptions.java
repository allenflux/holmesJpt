package dev.holmes;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 只预解析全局配置文件选项，不扫描问题正文或其它选项的值。 */
record StartupOptions(List<String> arguments, Path envFile, boolean explicitEnvFile, boolean helpRequested) {
    private static final Set<String> VALUE_FLAGS = Set.of("--tools", "--runbooks", "--max-steps", "--port",
            "--host", "--tasks", "--log-file", "--data-dir", "--request", "--output");

    StartupOptions { arguments = List.copyOf(arguments); }

    static StartupOptions parse(String[] input) {
        List<String> arguments = new ArrayList<>();
        Path envFile = Path.of(".env");
        boolean explicit = false, help = false;
        for (int i = 0; i < input.length; i++) {
            String argument = input[i];
            if (argument.equals("--")) {
                // 标准字面量分隔符之后不再把问题文本识别为全局选项。
                for (; i < input.length; i++) arguments.add(input[i]);
                break;
            }
            if (argument.equals("--env-file")) {
                if (++i == input.length || input[i].isBlank() || input[i].startsWith("--")) {
                    throw new IllegalArgumentException("--env-file 缺少有效文件路径");
                }
                try { envFile = Path.of(input[i]); }
                catch (InvalidPathException e) { throw new IllegalArgumentException("--env-file 文件路径无效"); }
                explicit = true; // 与其它命令行选项一致，重复指定时最后一个生效。
                continue;
            }
            if (argument.equals("--help") || arguments.isEmpty() && argument.equals("help")) help = true;
            arguments.add(argument);
            if (VALUE_FLAGS.contains(argument) && i + 1 < input.length) arguments.add(input[++i]);
        }
        return new StartupOptions(arguments, envFile, explicit, help || arguments.isEmpty());
    }

    @Override public String toString() {
        return "StartupOptions[argumentCount=" + arguments.size() + ", explicitEnvFile=" + explicitEnvFile + ", helpRequested=" + helpRequested + "]";
    }
}
