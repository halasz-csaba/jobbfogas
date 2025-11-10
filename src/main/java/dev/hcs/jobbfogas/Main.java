package dev.hcs.jobbfogas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final Path WORK_PATH = Path.of("work");

    /**
     * @param args:
     *            arg0: jobName - the jobPath will be WORK_PATH + jobName
     *            jobPath must contain a file named 00_searchlink.txt
     *            if jobPath contains more files (intermediate results), they will be reused, skipping the given step
     */
    public static void main(String[] args) {

        JobbfogasCli cli = CommandLine.populateCommand(new JobbfogasCli(), args);
        if(cli.helpRequested) {
            CommandLine cmd = new CommandLine(cli);
            cmd.usage(System.out);
            return;
        }

        Path jobPath = resolveJobPath(cli.jobName);
        String deepSeekApiKey = System.getenv("DEEPSEEK_API_KEY");
        if(deepSeekApiKey == null) {
            deepSeekApiKey = cli.deepSeekApiKey;
        }
        Config cfg = new Config(jobPath, deepSeekApiKey);
        Jobbfogas jobbfogas = new Jobbfogas(cfg);
        jobbfogas.run();

//        Set<String> links = new HashSet<>();
//        boolean pageHasLinks = true;
//        int paginationIdx = 0;
//        while (pageHasLinks) {
//            paginationIdx++;
//            String searchPageUrl = SEARCH_URL + "&o=" + paginationIdx;
//            LOGGER.info("Fetching links from {}", searchPageUrl);
//            Document doc = HtmlFetcher.fetchHtml(searchPageUrl);
//            Set<String> newLinks = JofogasParser.getLinksFromSearchResultPage(doc);
//            pageHasLinks = !newLinks.isEmpty();
//            links.addAll(newLinks);
//        }
//        LOGGER.info("found {} links in {} pages", links.size(), paginationIdx);
    }

    private static Path resolveJobPath(String jobName) {
        Path jobPath = WORK_PATH.resolve(jobName);
        if(!Files.exists(jobPath) || !Files.isDirectory(jobPath)) {
            throw new IllegalArgumentException("job path {} is not an existing directory");
        }
        return jobPath;
    }

}
