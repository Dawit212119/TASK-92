package com.civicworks.service;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

/**
 * Sanitizes HTML bodies submitted through the content API.
 *
 * Allows a safe subset of HTML used in rich civic-content pages:
 * headings, paragraphs, lists, emphasis, blockquotes, code snippets,
 * and hyperlinks (with HTTPS/HTTP/mailto only).  Everything else —
 * scripts, iframes, form elements, event handlers — is stripped.
 */
@Service
public class HtmlSanitizerService {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // Structural / semantic
            .allowElements("p", "br", "hr",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li",
                    "dl", "dt", "dd",
                    "blockquote", "pre", "code", "kbd",
                    "table", "thead", "tbody", "tr", "th", "td",
                    "caption", "colgroup", "col")
            // Inline emphasis
            .allowElements("strong", "b", "em", "i", "u", "s", "del", "ins",
                    "mark", "small", "sub", "sup", "abbr", "cite", "q", "span")
            // Links — restrict to safe URL schemes only
            .allowElements("a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href").onElements("a")
            .allowAttributes("title").onElements("a", "abbr")
            .allowAttributes("target").matching(java.util.regex.Pattern.compile("^_blank$")).onElements("a")
            // Tables
            .allowAttributes("colspan", "rowspan", "scope").onElements("th", "td")
            // Generic safe styling (no inline style — avoids CSS injection)
            .allowAttributes("class").onElements(
                    "p", "span", "div", "ul", "ol", "li",
                    "table", "th", "td", "tr", "pre", "code",
                    "h1", "h2", "h3", "h4", "h5", "h6")
            // Images (src restricted to HTTPS only, no javascript: or data: URIs)
            .allowElements("img")
            .allowUrlProtocols("https")
            .allowAttributes("src", "alt", "width", "height").onElements("img")
            .toFactory();

    /**
     * Returns a sanitized copy of {@code html}.  If {@code html} is null or
     * blank, returns null so callers can distinguish "no body" from "empty body".
     */
    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        return POLICY.sanitize(html);
    }
}
