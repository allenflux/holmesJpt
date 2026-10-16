package dev.holmes.logs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 防止可变目录的符号链接把检查点和临时日志引到配置范围之外。 */
final class StoragePaths {
    private StoragePaths() { }
    static Path createDirectory(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        check(path);
        Files.createDirectories(path);
        check(path);
        return path;
    }
    private static void check(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent()) {
            if (!Files.isSymbolicLink(parent)) continue;
            // macOS 的两个系统别名是固定入口，保留常用 --data-dir /tmp/... 的行为。
            if (parent.equals(Path.of("/tmp")) && parent.toRealPath().equals(Path.of("/private/tmp"))) continue;
            if (parent.equals(Path.of("/var")) && parent.toRealPath().equals(Path.of("/private/var"))) continue;
            throw new IOException("日志存储路径不能经过自定义符号链接");
        }
    }
}
