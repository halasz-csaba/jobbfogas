package dev.hcs.jobbfogas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import dev.hcs.jobbfogas.filecache.CacheableStep;
import dev.hcs.jobbfogas.filecache.CacheableProcessRunner;
import dev.hcs.jobbfogas.filecache.ProcessContext;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;


public class Jobbfogas {

    private static final Logger LOGGER = LoggerFactory.getLogger(Jobbfogas.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Config cfg;

    public Jobbfogas(Config cfg) {
        this.cfg = cfg;
    }

    public void run() {

        List<CacheableStep<JobbfogasCtx>> steps = List.of(
                new Step00_GetSearchLink(),
                new Step01_FetchItemLinks(),
                new Step02_FetchItems(),
                new Step03_ParseItems(),
                new Step04_AnalyzeWithAi(),
                new Step05_ProcessAiAnalysis(),
                new Step06_CreateCsv()
        );
        JobbfogasCtx ctx = new JobbfogasCtx();
        CacheableProcessRunner<JobbfogasCtx> runner = new CacheableProcessRunner<>(cfg.jobPath(), ctx, steps);
        runner.start();
    }

    static class JobbfogasCtx implements ProcessContext {
        private String searchLink;
        private Set<String> itemLinks;
        private Map<String, String> itemHtmls;
        private Map<String, Map<String, String>> parsedItems;
        private Map<String, String> aiAnalysisRaw;
        private Map<String, Map<String, String>> analyzedItems;
        private List<List<String>> resultTable;
    }

    static class Step00_GetSearchLink implements CacheableStep<JobbfogasCtx> {

        @Override
        public String getName() {
            return "GetSearchLink";
        }

        @Override
        public String getCacheFileName() {
            return "00_searchlink.txt";
        }

        @Override
        public void readCache(Path searchLinkCache, JobbfogasCtx ctx) {
            List<String> lines;
            try (Stream<String> linesStream = Files.lines(searchLinkCache)) {
                lines = linesStream.toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (lines.isEmpty()) {
                throw new RuntimeException(searchLinkCache + " has no lines");
            }
            if (lines.size() > 1) {
                LOGGER.warn("{} contains multiple lines, using only the first one", searchLinkCache);
            }
            ctx.searchLink = lines.getFirst();
            LOGGER.info("Search link found: {}", ctx.searchLink);
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            throw new UnsupportedOperationException(getCacheFileName() + " file must pre-exist");
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            throw new UnsupportedOperationException(getCacheFileName() + " file must pre-exist");
        }
    }

    static class Step01_FetchItemLinks implements CacheableStep<JobbfogasCtx> {

        @Override
        public String getName() {
            return "FetchItemLinks";
        }

        @Override
        public String getCacheFileName() {
            return "01_itemlinks.txt";
        }

        @Override
        public void readCache(Path itemLinksCache, JobbfogasCtx ctx) {
            ctx.itemLinks = new HashSet<>();
            try (Stream<String> linesStream = Files.lines(itemLinksCache)) {
                linesStream.filter(l -> !l.isBlank()).forEach(ctx.itemLinks::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            LOGGER.info("{} links read from {}", ctx.itemLinks.size(), itemLinksCache);
        }

        @Override
        public void writeCache(Path itemLinksCache, JobbfogasCtx ctx) {
            try (PrintWriter printWriter = new PrintWriter(itemLinksCache.toFile())) {
                ctx.itemLinks.forEach(printWriter::println);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            LOGGER.info("Links saved to {}", itemLinksCache);
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            ctx.itemLinks = new HashSet<>();
            boolean pageHasLinks = true;
            int paginationIdx = 0;
            while (pageHasLinks) {
                paginationIdx++;
                String searchPageUrl = ctx.searchLink + "&o=" + paginationIdx;
                LOGGER.info("Fetching links from {}", searchPageUrl);
                Document doc = HtmlFetcher.fetchHtml(searchPageUrl);
                Set<String> newLinks = JofogasHelper.getLinksFromSearchResultPage(doc);
                pageHasLinks = !newLinks.isEmpty();
                ctx.itemLinks.addAll(newLinks);
            }
            if(ctx.itemLinks.isEmpty()){
                throw new RuntimeException("No items links found");
            }
            LOGGER.info("Found {} links in {} pages", ctx.itemLinks.size(), paginationIdx);
        }
    }

    private class Step02_FetchItems implements CacheableStep<JobbfogasCtx> {
        @Override
        public String getName() {
            return "FetchItems";
        }

        @Override
        public String getCacheFileName() {
            return "02_items.json";
        }

        @Override
        public void readCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                ctx.itemHtmls = mapper.readValue(cacheFilePath.toFile(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                mapper.writeValue(cacheFilePath.toFile(), ctx.itemHtmls);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            ctx.itemHtmls = new ConcurrentHashMap<>();
            ctx.itemLinks.parallelStream().forEach(itemLink -> {
                LOGGER.info("Fetching {}", itemLink);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(itemLink))
                        .build();
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        LOGGER.warn("Failed to fetch html from {}: ResponseCode={}", itemLink, response.statusCode());
                    } else {
                        ctx.itemHtmls.put(itemLink, response.body());
                    }
                } catch (IOException | InterruptedException e) {
                    LOGGER.warn("Failed to fetch html from {}", itemLink, e);
                }
            });
        }
    }

    private class Step03_ParseItems implements CacheableStep<JobbfogasCtx> {
        @Override
        public String getName() {
            return "ParseItems";
        }

        @Override
        public String getCacheFileName() {
            return "03_parseditems.json";
        }

        @Override
        public void readCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                ctx.parsedItems = mapper.readValue(cacheFilePath.toFile(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                mapper.writeValue(cacheFilePath.toFile(), ctx.parsedItems);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            ctx.parsedItems = new ConcurrentHashMap<>();
            ctx.itemHtmls.keySet().parallelStream().forEach((String itemLink) -> {
                String htmlStr = ctx.itemHtmls.get(itemLink);
                String jsonStr = JofogasHelper.extractScriptJson(htmlStr);
                Map<String, String> itemAttrs = JofogasHelper.extractItemAttributes(jsonStr);
                ctx.parsedItems.put(itemLink, itemAttrs);
            });
        }
    }

    private class Step04_AnalyzeWithAi implements CacheableStep<JobbfogasCtx> {
        @Override
        public String getName() {
            return "AnalyzeWithAi";
        }

        @Override
        public String getCacheFileName() {
            return "04_aianalysisraw.json";
        }

        @Override
        public void readCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                ctx.aiAnalysisRaw = mapper.readValue(cacheFilePath.toFile(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                mapper.writeValue(cacheFilePath.toFile(), ctx.aiAnalysisRaw);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<String> ATTRS_FOR_AI = List.of("subject", "body", "capacity", "computer_cpu_type",
                "computer_os", "computer_acc_brand");

//        // TODO make this configurable
//        private static final String USER_ASPECTS = """
//                - 150.000 Ft büdzsé
//                - jól fusson rajta minecraft, fortnite (fullHD 60fps)
//                - ne avuljon el még 3-4 évig
//                """;

        @Override
        public void execute(JobbfogasCtx ctx) {
            ctx.aiAnalysisRaw = new ConcurrentHashMap<>();
            JobbfogasAiAssistant assistant = new JobbfogasDeepseekInterpreter(cfg.deepSeekApiKey());
            ctx.parsedItems.keySet().parallelStream().forEach((String itemLink) -> {
                Map<String, String> parsedItem = ctx.parsedItems.get(itemLink);
                Map<String, String> itemForAi = new HashMap<>();
                ATTRS_FOR_AI.forEach(key -> itemForAi.put(key, parsedItem.get(key)));
                LOGGER.info("Sending AI analysis request for " + itemLink);
                String aiResult = null;
                try {
                    aiResult = assistant.analyzeItem(itemForAi);
                } catch (Exception e){
                    LOGGER.error("Failed to get AI analysis for " + itemLink);
                }
                if(aiResult != null) {
                    ctx.aiAnalysisRaw.put(itemLink, aiResult);
                }
            });
        }
    }

    private class Step05_ProcessAiAnalysis implements CacheableStep<JobbfogasCtx> {
        @Override
        public String getName() {
            return "ProcessAiAnalysis";
        }

        @Override
        public String getCacheFileName() {
            return "05_analyzeditems.json";
        }

        @Override
        public void readCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                ctx.analyzedItems = mapper.readValue(cacheFilePath.toFile(), new TypeReference<>() {
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            try {
                mapper.writeValue(cacheFilePath.toFile(), ctx.analyzedItems);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            ctx.analyzedItems = new ConcurrentHashMap<>();
            ctx.parsedItems.keySet().parallelStream().forEach((String itemLink) -> {
                Map<String, String> parsedItem = ctx.parsedItems.get(itemLink);
                String analysisRaw = ctx.aiAnalysisRaw.get(itemLink);
                Map<String, String> analysisResult;
                if (analysisRaw == null) {
                    LOGGER.error("No AI analysis found for {}", itemLink);
                    analysisResult = Map.of();
                } else {
                    try {
                        analysisRaw = analysisRaw.strip();
                        if(analysisRaw.startsWith("```json")) {
                            analysisRaw = analysisRaw.substring(7);
                        }
                        if(analysisRaw.endsWith("```")) {
                            analysisRaw = analysisRaw.substring(0, analysisRaw.length()-3);
                        }
                        analysisResult = mapper.readValue(analysisRaw, new TypeReference<>() {
                        });
                    } catch (JsonProcessingException e) {
                        LOGGER.error("Failed to parse AI analysis for {}", itemLink);
                        analysisResult = Map.of();
                    }
                }
                Map<String, String> mergedResult = new HashMap<>(parsedItem);
                mergedResult.putAll(analysisResult);
                ctx.analyzedItems.put(itemLink, mergedResult);
            });
        }
    }

    private static class Step06_CreateCsv implements CacheableStep<JobbfogasCtx> {
        @Override
        public String getName() {
            return "CreateCsv";
        }

        @Override
        public String getCacheFileName() {
            return "06_results.csv";
        }

        @Override
        public void readCache(Path cacheFilePath, JobbfogasCtx ctx) {
            LOGGER.warn("This is the last step, skipping reading the csv");
        }

        @Override
        public void writeCache(Path cacheFilePath, JobbfogasCtx ctx) {
            writeCsv(cacheFilePath, ctx.resultTable);
        }

        @Override
        public void execute(JobbfogasCtx ctx) {
            Set<String> columns = new HashSet<>(Set.of("link"));
            ctx.analyzedItems.values().forEach(map -> columns.addAll(map.keySet()));
            ctx.resultTable = new ArrayList<>();
            ctx.resultTable.add(columns.stream().toList());
            for (String itemLink : ctx.analyzedItems.keySet()) {
                Map<String, String> attrs = ctx.analyzedItems.get(itemLink);
                List<String> row = new ArrayList<>(attrs.size() + 1);
                for (String column : columns) {
                    if (column.equals("link")) {
                        row.add(itemLink);
                    } else {
                        row.add(attrs.get(column));
                    }
                }
                ctx.resultTable.add(row);
            }
        }
    }

    private static void writeCsv(Path path, List<List<String>> data) {
        System.out.println("Writing " + path);
        List<String[]> data2 = data.stream().map((List<String> row) -> row.toArray(new String[]{})).toList();
        try (CSVWriter writer = new CSVWriter(new FileWriter(path.toString()))) {
            writer.writeAll(data2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
