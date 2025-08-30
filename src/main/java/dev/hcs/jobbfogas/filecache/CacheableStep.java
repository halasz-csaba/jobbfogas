package dev.hcs.jobbfogas.filecache;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface CacheableStep<T extends ProcessContext> {
    String getName();
    String getCacheFileName();
    void readCache(Path cacheFilePath, T ctx);
    void writeCache(Path cacheFilePath, T ctx);
    void execute(T ctx);
}
