package dev.holmes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 精简版直接加载少量本地 Markdown；不引入检索服务。 */
public final class Runbooks {
    private Runbooks() { }

    public static String load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Runbook 目录不存在：" + directory);
        StringBuilder text = new StringBuilder();
        try (var files = Files.list(directory)) {
            var paths = files.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
            if (paths.size() > 20) throw new IOException("精简版最多加载 20 个 Runbook");
            for (Path path : paths) {
                if (Files.size(path) > 80000) throw new IOException("Runbook 文件过大：" + path.getFileName());
                text.append("\n## ").append(path.getFileName()).append('\n')
                        .append(Files.readString(path, StandardCharsets.UTF_8));
                if (text.length() > 20000) throw new IOException("Runbook 总长度超过 20000 字符，请精简内容");
            }
        }
        return text.toString();
    }
}
