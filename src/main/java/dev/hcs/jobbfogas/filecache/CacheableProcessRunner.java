package dev.hcs.jobbfogas.filecache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Executes a process of cacheable steps. If a step's artifact is found in the file cache, it's not executed, but read.
 * T typeref: The type of the Context object
 * It is mainly useful to save results of costly tasks and
 * re-use them on a next run - e.g. during development, where running later tasks multiple times should not require
 * running the early tasks also.
 */
public class CacheableProcessRunner<T extends ProcessContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheableProcessRunner.class);

    private final Path cacheDir;
    private final T ctx;
    private final List<CacheableStep<T>> steps;

    public CacheableProcessRunner(Path cacheDir, T ctx, List<CacheableStep<T>> steps) {
        Objects.requireNonNull(cacheDir);
        Objects.requireNonNull(ctx);
        Objects.requireNonNull(steps);
        if (!Files.exists(cacheDir)) {
            throw new IllegalArgumentException("cacheDir does exist: " + cacheDir);
        } else if (!Files.isDirectory(cacheDir)) {
            throw new IllegalArgumentException("cacheDir is not a directory: " + cacheDir);
        } else if (!Files.isWritable(cacheDir)) {
            throw new IllegalArgumentException("cacheDir is not writeable: " + cacheDir);
        }
        this.cacheDir = cacheDir;

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps is empty");
        }
        this.steps = List.copyOf(steps);
        this.ctx = ctx;
    }

    /**
     * Runs the process steps, invokes the cacheReaders up until a step is not cached, then invokes the executors for
     * the remaining steps
     */
    public void start() {
        LOGGER.info("CacheableProcessRunner started");
        Map<String, Path> cacheFiles = getCacheFiles();
        boolean cacheExhausted = false;
        for (CacheableStep<T> step : steps) {
            String cacheFileName = step.getCacheFileName();
            if (!cacheExhausted && cacheFiles.containsKey(cacheFileName)) {
                LOGGER.info("Reading cached result of {}", step.getName());
                step.readCache(cacheFiles.get(cacheFileName), ctx);
            } else {
                cacheExhausted = true;
                LOGGER.info("Executing {}", step.getName());
                step.execute(ctx);
                Path cacheFilePath = cacheDir.resolve(cacheFileName);
                LOGGER.info("Writing result of {} to cache: {}", step.getName(), cacheFilePath);
                step.writeCache(cacheFilePath, ctx);
            }
        }
        LOGGER.info("CacheableProcessRunner finished");
    }

    /**
     * @return the existing files' path in the cache directory, by filenames
     */
    private Map<String, Path> getCacheFiles() {
        try (var stream = Files.list(cacheDir)) {
            return stream.filter(Files::isRegularFile)
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
