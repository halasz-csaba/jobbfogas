package dev.hcs.jobbfogas;

import picocli.CommandLine;

public class JobbfogasCli {
    @CommandLine.Parameters(index = "0", paramLabel = "JOB_NAME", description = "The name of the job directory")
    String jobName;

    @CommandLine.Option(names = { "--deepseek-api-key" }, description = "DeepSeek API key")
    String deepSeekApiKey = null;

    @CommandLine.Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
    boolean helpRequested = false;
}
