package dev.hcs.jobbfogas;

import java.nio.file.Path;

public record Config(Path jobPath, String deepSeekApiKey) {
}
