package dev.holmes.logs;

import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogQuery;
import java.io.IOException;
import java.time.Instant;

/** 适配器必须遵守批次剩余记录、响应字节与绝对截止时间预算。 */
public interface LogSource {
    FetchResult fetch(LogQuery query, int maxEvents, long maxBytes, Instant deadline)
            throws IOException, InterruptedException;
}
