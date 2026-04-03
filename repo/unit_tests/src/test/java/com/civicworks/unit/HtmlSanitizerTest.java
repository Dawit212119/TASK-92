package com.civicworks.unit;

import com.civicworks.service.HtmlSanitizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HtmlSanitizerService.
 *
 * Verifies that dangerous HTML is stripped while safe structural markup is
 * preserved, and that null/blank inputs are handled gracefully.
 */
class HtmlSanitizerTest {

    private HtmlSanitizerService sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new HtmlSanitizerService();
    }

    // ── Null / blank passthrough ─────────────────────────────────────────────

    @Test
    void nullInput_returnsNull() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void blankInput_returnsNull() {
        assertThat(sanitizer.sanitize("   ")).isNull();
    }

    @Test
    void emptyString_returnsNull() {
        assertThat(sanitizer.sanitize("")).isNull();
    }

    // ── XSS attack vectors stripped ──────────────────────────────────────────

    @Test
    void scriptTag_isStripped() {
        String result = sanitizer.sanitize("<p>Hello</p><script>alert('xss')</script>");
        assertThat(result).doesNotContain("<script");
        assertThat(result).doesNotContain("alert");
        assertThat(result).contains("Hello");
    }

    @Test
    void inlineEventHandler_isStripped() {
        String result = sanitizer.sanitize("<p onclick=\"evil()\">Click me</p>");
        assertThat(result).doesNotContain("onclick");
        assertThat(result).doesNotContain("evil");
        assertThat(result).contains("Click me");
    }

    @Test
    void iframeTag_isStripped() {
        String result = sanitizer.sanitize("<iframe src=\"http://evil.com\"></iframe><p>OK</p>");
        assertThat(result).doesNotContain("<iframe");
        assertThat(result).contains("OK");
    }

    @Test
    void javascriptHref_isStripped() {
        String result = sanitizer.sanitize("<a href=\"javascript:alert(1)\">link</a>");
        // href with javascript: protocol must be removed
        assertThat(result).doesNotContain("javascript:");
    }

    @Test
    void dataUriInImg_isStripped() {
        String result = sanitizer.sanitize("<img src=\"data:image/svg+xml,<svg/onload=alert(1)>\">");
        // data: URIs must be stripped (only https allowed for img src)
        assertThat(result == null || !result.contains("data:")).isTrue();
    }

    @Test
    void styleAttribute_isStripped() {
        String result = sanitizer.sanitize("<p style=\"background:url(javascript:evil())\">text</p>");
        assertThat(result).doesNotContain("style=");
        assertThat(result).contains("text");
    }

    // ── Safe markup preserved ────────────────────────────────────────────────

    @Test
    void paragraphAndBold_arePreserved() {
        String html = "<p>This is <strong>important</strong> text.</p>";
        String result = sanitizer.sanitize(html);
        assertThat(result).contains("<p>");
        assertThat(result).contains("<strong>");
        assertThat(result).contains("important");
    }

    @Test
    void headings_arePreserved() {
        String result = sanitizer.sanitize("<h1>Title</h1><h2>Sub</h2>");
        assertThat(result).contains("<h1>");
        assertThat(result).contains("<h2>");
    }

    @Test
    void orderedAndUnorderedList_arePreserved() {
        String result = sanitizer.sanitize("<ul><li>A</li><li>B</li></ul>");
        assertThat(result).contains("<ul>");
        assertThat(result).contains("<li>");
    }

    @Test
    void httpsLink_isPreserved() {
        String result = sanitizer.sanitize("<a href=\"https://example.com\">Link</a>");
        assertThat(result).contains("href=\"https://example.com\"");
    }

    @Test
    void httpLink_isPreserved() {
        String result = sanitizer.sanitize("<a href=\"http://example.com\">Link</a>");
        assertThat(result).contains("http://example.com");
    }

    @Test
    void mailtoLink_isPreserved() {
        String result = sanitizer.sanitize("<a href=\"mailto:a@b.com\">Email</a>");
        // OWASP sanitizer HTML-encodes '@' as '&#64;' inside href values.
        // Either the raw '@' or the entity form is acceptable.
        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("mailto:a@b.com"),
                r -> assertThat(r).contains("mailto:a&#64;b.com")
        );
    }

    @Test
    void httpsImage_isPreserved() {
        String result = sanitizer.sanitize("<img src=\"https://example.com/img.png\" alt=\"pic\">");
        assertThat(result).contains("https://example.com/img.png");
    }

    @Test
    void blockquoteAndCode_arePreserved() {
        String result = sanitizer.sanitize("<blockquote><code>int x = 1;</code></blockquote>");
        assertThat(result).contains("<blockquote>");
        assertThat(result).contains("<code>");
    }

    @Test
    void plainText_returnedUnchanged() {
        String result = sanitizer.sanitize("Just plain text with no HTML.");
        assertThat(result).contains("Just plain text with no HTML.");
    }
}
