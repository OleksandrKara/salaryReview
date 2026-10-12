package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** What the component extractor needs from a page, built either from raw HTML (direct fetch) or
 * from the compact JSON our DataForSEO {@code custom_js} returns (fallback). Keeping both paths on
 * one shape means the detection rules are written and tested once (design.md D10). */
public record PageSnapshot(List<String> jsonLd, List<String> headings, List<String> images, List<String> media,
        List<String> links, String text) {

    /** The script DataForSEO runs in the page; mirrors {@link #fromHtml}. Sizes are capped so the
     * response stays small. */
    public static final String CUSTOM_JS = "JSON.stringify({"
            + "ld:[...document.querySelectorAll('script[type=\"application/ld+json\"]')].map(s=>s.textContent.slice(0,4000)).slice(0,8),"
            + "h:[...document.querySelectorAll('h1,h2,h3')].map(h=>h.tagName+'|'+(h.innerText||'').trim().slice(0,120)).slice(0,60),"
            + "img:[...document.images].map(i=>(i.getAttribute('src')||'').slice(-120)+'|'+(i.alt||'').slice(0,80)).slice(0,80),"
            + "fr:[...document.querySelectorAll('iframe,video,source')].map(e=>(e.src||'').slice(0,150)).slice(0,20),"
            + "a:[...document.querySelectorAll('a,button')].map(a=>(a.innerText||'').trim().slice(0,40)+'|'+(a.getAttribute('href')||'').slice(0,150)).slice(0,150),"
            + "t:(document.body.innerText||'').slice(0,8000)})";

    public static PageSnapshot fromHtml(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        List<String> ld = new ArrayList<>();
        for (Element s : doc.select("script[type=application/ld+json]")) ld.add(cap(s.data(), 4000));
        List<String> headings = new ArrayList<>();
        for (Element h : doc.select("h1,h2,h3")) headings.add(h.tagName().toUpperCase() + "|" + cap(h.text().trim(), 120));
        List<String> images = new ArrayList<>();
        for (Element i : doc.select("img")) {
            String src = i.attr("src").isEmpty() ? i.attr("data-src") : i.attr("src");
            images.add(tail(src, 120) + "|" + cap(i.attr("alt"), 80));
        }
        List<String> media = new ArrayList<>();
        for (Element e : doc.select("iframe,video,source")) media.add(cap(e.attr("src"), 150));
        List<String> links = new ArrayList<>();
        for (Element a : doc.select("a,button")) links.add(cap(a.text().trim(), 40) + "|" + cap(a.attr("href"), 150));
        doc.select("script,style,noscript").remove();
        String text = doc.body() == null ? "" : doc.body().text();
        return new PageSnapshot(capList(ld, 8), capList(headings, 60), capList(images, 80), capList(media, 20), capList(links, 150), cap(text, 30000));
    }

    public static PageSnapshot fromCustomJs(JsonNode n) {
        return new PageSnapshot(strings(n.path("ld")), strings(n.path("h")), strings(n.path("img")), strings(n.path("fr")),
                strings(n.path("a")), n.path("t").asText(""));
    }

    public int wordCount() {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : arr) if (x.isTextual()) out.add(x.asText());
        return out;
    }

    private static String cap(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    private static String tail(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(s.length() - n) : s;
    }

    private static List<String> capList(List<String> l, int n) {
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }
}
