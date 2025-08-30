package dev.hcs.jobbfogas;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.UncheckedIOException;

public class HtmlFetcher {

    public static Document fetchHtml(String url){
        try {
            return Jsoup.connect(url).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
