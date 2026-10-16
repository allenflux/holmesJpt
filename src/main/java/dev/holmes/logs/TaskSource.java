package dev.holmes.logs;

import java.io.IOException;

/** 任务来源只提供只读状态；每个批次选定的名单随后写入检查点。 */
public interface TaskSource {
    TaskSnapshot.Selection select(LogRequest request) throws IOException, InterruptedException;
    default boolean asynchronousSelection() { return false; }

    static TaskSource from(LogConfig config, java.util.Map<String, String> env) {
        return config.tasksFile() != null ? file(config) : new SchedulerTaskSource(config, SchedulerConfig.from(env));
    }

    static TaskSource file(LogConfig config) {
        return request -> new TaskSnapshot().select(config.tasksFile(), request);
    }
}
