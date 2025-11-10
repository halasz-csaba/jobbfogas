package dev.hcs.jobbfogas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Helper methods to deal with Jofogas.hu resources
 */
public class JofogasHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(JofogasHelper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Set<String> getLinksFromSearchResultPage(Document doc) {
        Set<String> links = new HashSet<>();

        // get links from text search list
        for (Element titleElement : doc.select("h3.item-title")) {
            Element a = titleElement.selectFirst("a");
            if (a == null) {
                LOGGER.error("No <a> found in titleElement: {}", titleElement);
            }
            String link = a.attr("href");
            links.add(link);
        }
        if (!links.isEmpty()) {
            return links;
        }

        // get links from category view
        for (Element titleElement : doc.select("a.MuiLink-root.MuiLink-underlineNone")) {
            Element a = titleElement.selectFirst("a");
            if (a == null) {
                LOGGER.error("No <a> found in titleElement: {}", titleElement);
            }
            String link = a.attr("href");
            links.add(link);
        }

        return links;
    }

    public static String extractScriptJson(String itemHtmlStr) {
        Document doc = Jsoup.parse(itemHtmlStr);
        Elements scriptNodes = doc.selectXpath("/html/body/script");
        if (scriptNodes.size() != 1) {
            throw new RuntimeException("Number of /html/body/script is not 1, but " + scriptNodes.size());
        }
        Element scriptNode = scriptNodes.getFirst();
        if (!scriptNode.attr("type").equals("application/json")) {
            throw new RuntimeException(
                    "<script>'s type attribute is not application/json, but " + scriptNode.attr("type"));
        }
        if (scriptNode.childNodeSize() != 1) {
            throw new RuntimeException(
                    "Number of child nodes of <script> is not 1, but " + scriptNode.childNodeSize());
        }
        Node childNode = scriptNode.childNode(0);
        if (!(childNode instanceof DataNode)) {
            throw new RuntimeException("Child node of <script> is not DataNode");
        }
        String data = ((DataNode) childNode).getWholeData();
        return data.strip();
    }

    public static Map<String, String> extractItemAttributes(String jsonStr) {
        Map<String, String> attrs = new HashMap<>();
        JsonNode json;
        try {
            json = MAPPER.readTree(jsonStr);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        int listTimeEpochSec = json.at("/props/pageProps/product/list_time/value").asInt();
        attrs.put("list_time", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(listTimeEpochSec)));
        attrs.put("region", json.at("/props/pageProps/product/region/label").asText());
        attrs.put("subject", json.at("/props/pageProps/product/subject").asText());
        attrs.put("body", json.at("/props/pageProps/product/body").asText());
        attrs.put("price_huf", json.at("/props/pageProps/product/price/value").asText());
        attrs.put("longitude", json.at("/props/pageProps/product/longitude").asText());
        attrs.put("latitude", json.at("/props/pageProps/product/latitude").asText());
        attrs.putAll(extractProdParams(json));
        return attrs;
    }

    /**
     * maps the parameters list section of the item json to a flat argument map
     * <pre>
     * "parameters": [
     * {
     *     "key": "zipcode",
     *         "label": "Irányítószám",
     *         "values": [
     *     {
     *         "value": "9495",
     *             "label": "Kópháza"
     *     }
     *     ],
     *     "order": 1
     * }, ...]
     * </pre>
     */
    private static Map<String, String> extractProdParams(JsonNode json) {
        Map<String, String> attrs = new HashMap<>();
        ArrayNode prodParams = (ArrayNode) json.at("/props/pageProps/product/parameters");
        for (JsonNode prodParam : prodParams) {
            ArrayNode values = (ArrayNode) prodParam.get("values");
            String valuesStr = switch (values.size()) {
                case 0 -> "";
                case 1 -> getProjParamValueAsString(values.get(0));
                default -> {
                    StringBuilder sb = new StringBuilder();
                    boolean first = true;
                    for (JsonNode projParamValue : values) {
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append(getProjParamValueAsString(projParamValue));
                        first = false;
                    }
                    yield sb.toString();
                }
            };
            attrs.put(prodParam.get("key").asText(), valuesStr);
        }
        return attrs;
    }

    private static String getProjParamValueAsString(JsonNode node) {
        JsonNode label = node.get("label");
        return (label == null ? node.get("value") : label).asText();
    }
}
