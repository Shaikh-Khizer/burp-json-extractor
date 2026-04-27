package com.jsonextractor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.geom.Rectangle2D;

/**
 * "JSON / JS" tab in Burp response editor.
 *
 * Features
 * ─ JSON mode (original):
 *   · Extracts and pretty-prints JSON blocks from any response body.
 *   · [Original | Decoded] toggle: URL-decodes then HTML-decodes the body.
 *   · Copy button, One-Dark syntax highlighting, line numbers.
 *
 * ─ JavaScript mode (new):
 *   · Extracts inline <script> blocks, external <script src="…"> references,
 *     javascript: URLs, and inline event-handler attributes.
 *   · Detects whole-body JS files (application/javascript etc.).
 *   · Beautifies minified JS: proper indentation, newlines, brace formatting.
 *   · Strips CDATA wrappers (<![CDATA[ … ]]>).
 *   · HTML-decode + URL-decode support (same [Original|Decoded] toggle).
 *   · JS-specific One-Dark syntax highlighting: keywords, strings, numbers,
 *     comments, regex literals, function calls, operators.
 *
 * Zero third-party dependencies.
 */
public class JsonExtractorTab implements ExtensionProvidedHttpResponseEditor {

    // ── Shared colours (One Dark) ─────────────────────────────────────────────
    private static final Color BG_COLOR      = new Color(0x282C34);
    private static final Color FG_COLOR      = new Color(0xABB2BF);
    private static final Color TOOLBAR_COLOR = new Color(0x21252B);
    private static final Color BORDER_COLOR  = new Color(0x3E4451);
    private static final Color COMMENT_COLOR = new Color(0x5C6370);
    private static final Color COLOR_KEY     = new Color(0x56B6C2);
    private static final Color COLOR_STRING  = new Color(0x98C379);
    private static final Color COLOR_NUMBER  = new Color(0xD19A66);
    private static final Color COLOR_BOOLEAN = new Color(0xC678DD);
    private static final Color COLOR_NULL    = new Color(0xE06C75);
    private static final Color COLOR_BRACKET = new Color(0xABB2BF);
    private static final Color BTN_ACTIVE    = new Color(0x528BFF);
    private static final Color BTN_INACTIVE  = new Color(0x3A3F4B);

    // ── JS-specific colours ───────────────────────────────────────────────────
    private static final Color JS_KEYWORD  = new Color(0xC678DD); // purple  — var/let/if/return…
    private static final Color JS_FUNCTION = new Color(0x61AFEF); // blue    — foo(…)
    private static final Color JS_REGEX    = new Color(0xE5C07B); // amber   — /pattern/flags
    private static final Color JS_OPERATOR = new Color(0x56B6C2); // cyan    — = + - * …

    // ── View modes ────────────────────────────────────────────────────────────
    private static final int MODE_JSON = 0;
    private static final int MODE_JS   = 1;

    // ── JS keywords for highlighting ──────────────────────────────────────────
    private static final Set<String> JS_KEYWORDS = new HashSet<>(Arrays.asList(
        "var", "let", "const", "function", "return", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "new", "delete", "typeof",
        "instanceof", "in", "of", "try", "catch", "finally", "throw", "class",
        "extends", "super", "this", "import", "export", "default", "from", "async",
        "await", "yield", "static", "get", "set", "null", "undefined", "true",
        "false", "void", "with", "debugger", "arguments", "prototype"
    ));

    // ── Swing ─────────────────────────────────────────────────────────────────
    private final JPanel      mainPanel;
    private final JTextPane   textPane;
    private final JScrollPane scrollPane;
    private final JLabel      statusLabel;
    private final JButton     decodeBtn;
    private final JButton     jsonModeBtn;
    private final JButton     jsModeBtn;

    // ── State ─────────────────────────────────────────────────────────────────
    @SuppressWarnings("unused")
    private final MontoyaApi api;
    private HttpResponse currentResponse;
    private String  lastRawBody = null;
    private boolean decodeMode  = false;
    private int     viewMode    = MODE_JSON;
    private int fontSize = 12; // default
        // ── Search state ──────────────────────────────────────────────────────────
    private final JTextField  searchField;
    private final JLabel      matchLabel;
    private final List<int[]> searchMatches = new ArrayList<>(); // [start, end] pairs
    private int               searchIndex   = -1;
    private static final Color SEARCH_HIGHLIGHT = new Color(0xE5C07B); // amber
    private static final Color SEARCH_CURRENT   = new Color(0xFF6B6B); // red-orange
    // ─────────────────────────────────────────────────────────────────────────

    public JsonExtractorTab(MontoyaApi api) {
        this.api = api;
        searchField = new JTextField(16);
        matchLabel  = new JLabel("");
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(BG_COLOR);

        // Text pane — init before toolbar lambdas capture it
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(BG_COLOR);
        textPane.setForeground(FG_COLOR);
        textPane.setCaretColor(Color.WHITE);
        textPane.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        textPane.setFont(pickMonoFont(fontSize));

        scrollPane = new JScrollPane(textPane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_COLOR);
        scrollPane.setRowHeaderView(new TextLineNumber(textPane));
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // ── Toolbar ───────────────────────────────────────────────────────────
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.setBackground(TOOLBAR_COLOR);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        statusLabel = new JLabel("No response loaded");
        statusLabel.setForeground(COMMENT_COLOR);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));

        // Mode toggle buttons
        jsonModeBtn = makeButton("{ } JSON");
        jsModeBtn   = makeButton("\u27E8/\u27E9 JavaScript");
        jsonModeBtn.setBackground(BTN_ACTIVE); // JSON active by default
        jsonModeBtn.addActionListener(e -> setViewMode(MODE_JSON));
        jsModeBtn.addActionListener(e -> setViewMode(MODE_JS));

        // Vertical separator
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 20));
        sep.setForeground(BORDER_COLOR);

        // Copy button — copies whatever is currently displayed
        JButton copyBtn = makeButton("Copy");
        copyBtn.addActionListener(e -> {
            String text = textPane.getText();
            if (text != null && !text.isEmpty()) {
                Toolkit.getDefaultToolkit()
                       .getSystemClipboard()
                       .setContents(new StringSelection(text), null);
                statusLabel.setText("Copied!");
            }
        });

        // Decode toggle (URL + HTML decode) — works in both modes
        decodeBtn = makeButton("Original");
        decodeBtn.setToolTipText("Toggle URL + HTML decoding of the full body before extraction");
        decodeBtn.addActionListener(e -> {
            decodeMode = !decodeMode;
            decodeBtn.setText(decodeMode ? "Decoded" : "Original");
            decodeBtn.setBackground(decodeMode ? BTN_ACTIVE : BTN_INACTIVE);
            if (lastRawBody != null) rerender(lastRawBody);
        });
        
        JButton fontDownBtn = makeButton("A−");
        JButton fontUpBtn   = makeButton("A+");
        fontDownBtn.setToolTipText("Decrease font size");
        fontUpBtn.setToolTipText("Increase font size");

        fontDownBtn.addActionListener(e -> {
            if (fontSize > 8) { fontSize--; applyFontSize(); }
        });
        fontUpBtn.addActionListener(e -> {
            if (fontSize < 32) { fontSize++; applyFontSize(); }
        });

        toolbar.add(statusLabel);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(jsonModeBtn);
        toolbar.add(jsModeBtn);
        toolbar.add(sep);
        toolbar.add(copyBtn);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(decodeBtn);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(fontDownBtn);
        toolbar.add(fontUpBtn);

        mainPanel.add(toolbar,    BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        // ── Search bar ────────────────────────────────────────────────────────────
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchBar.setBackground(TOOLBAR_COLOR);
        searchBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));

        searchField.setBackground(new Color(0x3A3F4B));
        searchField.setForeground(Color.WHITE);
        searchField.setCaretColor(Color.WHITE);
        searchField.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        searchField.setFont(new Font("Monospaced", Font.PLAIN, 11));

        matchLabel.setForeground(COMMENT_COLOR);
        matchLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JButton prevBtn = makeButton("▲");
        JButton nextBtn = makeButton("▼");
        JButton clearBtn = makeButton("✕");

        searchBar.add(new JLabel("  Find:") {{ setForeground(COMMENT_COLOR); setFont(new Font("SansSerif", Font.PLAIN, 11)); }});
        searchBar.add(searchField);
        searchBar.add(prevBtn);
        searchBar.add(nextBtn);
        searchBar.add(matchLabel);
        searchBar.add(Box.createHorizontalStrut(8));
        searchBar.add(clearBtn);

        mainPanel.add(searchBar, BorderLayout.SOUTH);

        // Wire up search actions
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { runSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { runSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { runSearch(); }
        });
        searchField.addActionListener(e -> navigateMatch(+1)); // Enter → next
        nextBtn.addActionListener(e -> navigateMatch(+1));
        prevBtn.addActionListener(e -> navigateMatch(-1));
        clearBtn.addActionListener(e -> { searchField.setText(""); clearSearchHighlights(); });
        
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    private void setViewMode(int mode) {
        viewMode = mode;
        jsonModeBtn.setBackground(mode == MODE_JSON ? BTN_ACTIVE : BTN_INACTIVE);
        jsModeBtn.setBackground(mode == MODE_JS   ? BTN_ACTIVE : BTN_INACTIVE);
        if (lastRawBody != null) rerender(lastRawBody);
    }

    // ── Montoya API ───────────────────────────────────────────────────────────

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.currentResponse = (requestResponse != null) ? requestResponse.response() : null;

        if (requestResponse == null || requestResponse.response() == null) {
            lastRawBody = null;
            showPlaceholder("No response loaded.");
            statusLabel.setText("No response");
            return;
        }

        lastRawBody = requestResponse.response().bodyToString();
        rerender(lastRawBody);
    }

    /**
     * Central render dispatcher.
     * Optionally URL + HTML decodes the body, then dispatches to the
     * appropriate renderer based on current viewMode.
     */
    private void rerender(String rawBody) {
        clearSearchHighlights();
        String body = decodeMode ? fullyDecode(rawBody) : rawBody;
        if (viewMode == MODE_JS) {
            rerenderJs(body);
        } else {
            rerenderJson(body);
        }
    }

    @Override public HttpResponse getResponse()                   { return currentResponse; }
    @Override public boolean isEnabledFor(HttpRequestResponse r)  { return true; }
    @Override public String    caption()                          { return "JSON-JS"; }
    @Override public Component uiComponent()                      { return mainPanel; }
    @Override public boolean   isModified()                       { return false; }

    @Override
    public Selection selectedData() {
        String sel = textPane.getSelectedText();
        if (sel == null || sel.isEmpty()) return null;
        return Selection.selection(ByteArray.byteArray(sel.getBytes()));
    }

    // =========================================================================
    // JSON MODE — all original logic, completely unchanged
    // =========================================================================

    private void rerenderJson(String body) {
        List<String> blocks = extractAndPrettyPrintJsonBlocks(body);

        if (blocks.isEmpty()) {
            showPlaceholder(
                "No JSON found in this response.\n\n" +
                "This tab looks for JSON objects {} and arrays []\n" +
                "anywhere in the body, regardless of Content-Type.\n\n" +
                "HTML, JS, and plain text are left as-is."
            );
            statusLabel.setText("No JSON found");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.size() > 1) {
                sb.append("// --- JSON Block ").append(i + 1)
                  .append(" of ").append(blocks.size()).append(" ---\n");
            }
            sb.append(blocks.get(i));
            if (i < blocks.size() - 1) sb.append("\n\n");
        }

        renderWithHighlighting(sb.toString());
        int n = blocks.size();
        statusLabel.setText("Found " + n + " JSON block" + (n > 1 ? "s" : "")
            + (decodeMode ? "  [decoded]" : ""));
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    private String fullyDecode(String s) {
        String result;
        try {
            result = URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            result = s;
        }
        result = htmlEntityDecode(result);
        return result;
    }

    private String htmlEntityDecode(String s) {
        if (!s.contains("&")) return s;

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') { out.append(c); i++; continue; }
            int semi = -1;
            for (int j = i + 1; j < s.length() && j < i + 12; j++) {
                if (s.charAt(j) == ';') { semi = j; break; }
            }
            if (semi == -1) { out.append(c); i++; continue; }
            String entity = s.substring(i, semi + 1);
            String decoded = decodeHtmlEntity(entity);
            if (decoded != null) { out.append(decoded); i = semi + 1; }
            else                 { out.append(c); i++; }
        }
        return out.toString();
    }

    private String decodeHtmlEntity(String entity) {
        if (entity.startsWith("&#") && !entity.startsWith("&#x") && entity.endsWith(";")) {
            try { return new String(Character.toChars(Integer.parseInt(entity, 2, entity.length() - 1, 10))); }
            catch (NumberFormatException ignored) {}
        }
        if (entity.startsWith("&#x") && entity.endsWith(";")) {
            try { return new String(Character.toChars(Integer.parseInt(entity, 3, entity.length() - 1, 16))); }
            catch (NumberFormatException ignored) {}
        }
        switch (entity) {
            case "&amp;":    return "&";
            case "&lt;":     return "<";
            case "&gt;":     return ">";
            case "&quot;":   return "\"";
            case "&apos;":   return "'";
            case "&nbsp;":   return "\u00A0";
            case "&copy;":   return "\u00A9";
            case "&reg;":    return "\u00AE";
            case "&trade;":  return "\u2122";
            case "&euro;":   return "\u20AC";
            case "&pound;":  return "\u00A3";
            case "&yen;":    return "\u00A5";
            case "&cent;":   return "\u00A2";
            case "&mdash;":  return "\u2014";
            case "&ndash;":  return "\u2013";
            case "&laquo;":  return "\u00AB";
            case "&raquo;":  return "\u00BB";
            case "&hellip;": return "\u2026";
            case "&lsquo;":  return "\u2018";
            case "&rsquo;":  return "\u2019";
            case "&ldquo;":  return "\u201C";
            case "&rdquo;":  return "\u201D";
            default:         return null;
        }
    }

    // ── JSON extraction ───────────────────────────────────────────────────────

    private List<String> extractAndPrettyPrintJsonBlocks(String body) {
        List<String> results = new ArrayList<>();
        if (body == null || body.isEmpty()) return results;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') {
                char close = (c == '{') ? '}' : ']';
                int end = findMatchingClose(body, i, c, close);
                if (end > i) {
                    String pretty = tryPrettyPrint(body.substring(i, end + 1));
                    if (pretty != null) { results.add(pretty); i = end + 1; continue; }
                }
            }
            i++;
        }
        return results;
    }

    private int findMatchingClose(String s, int start, char open, char close) {
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped)               { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true;  continue; }
            if (c == '"')              { inString = !inString; continue; }
            if (inString)              continue;
            if      (c == open)  depth++;
            else if (c == close && --depth == 0) return i;
        }
        return -1;
    }

    private String tryPrettyPrint(String raw) {
        try { return prettyPrint(raw.trim()); } catch (Exception e) { return null; }
    }

    private String prettyPrint(String json) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped)        { escaped = false; }
                else if (c == '\\') { escaped = true; }
                else if (c == '"')  { inString = false; }
                continue;
            }
            switch (c) {
                case '"': inString = true; out.append(c); break;
                case '{': case '[':
                    out.append(c);
                    if (!nextNonWhitespaceIsClose(json, i + 1, c)) {
                        indent++; out.append('\n'); appendIndent(out, indent);
                    }
                    break;
                case '}': case ']':
                    indent = Math.max(0, indent - 1);
                    out.append('\n'); appendIndent(out, indent); out.append(c); break;
                case ',':
                    out.append(c); out.append('\n'); appendIndent(out, indent); break;
                case ':':
                    out.append(": "); break;
                default:
                    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') out.append(c); break;
            }
        }
        String result = out.toString().trim();
        if (result.isEmpty() || (result.charAt(0) != '{' && result.charAt(0) != '['))
            throw new IllegalArgumentException("Not JSON");
        return result;
    }

    private boolean nextNonWhitespaceIsClose(String json, int from, char open) {
        char close = (open == '{') ? '}' : ']';
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            return c == close;
        }
        return false;
    }

    private void appendIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    // ── JSON syntax highlighting ──────────────────────────────────────────────

    private void renderWithHighlighting(String json) {
        StyledDocument doc = textPane.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException e) { return; }
        int i = 0, len = json.length();
        while (i < len) {
            char c = json.charAt(i);
            if (c == '/' && i + 1 < len && json.charAt(i + 1) == '/') {
                int end = json.indexOf('\n', i); if (end < 0) end = len;
                append(doc, json.substring(i, end), COMMENT_COLOR, false, true); i = end; continue;
            }
            if (c == '"') {
                int end = i + 1;
                while (end < len) {
                    char x = json.charAt(end);
                    if (x == '\\') { end += 2; continue; }
                    if (x == '"')  { end++; break; }
                    end++;
                }
                end = Math.min(end, len);
                int peek = end;
                while (peek < len && (json.charAt(peek) == ' ' || json.charAt(peek) == '\t')) peek++;
                boolean isKey = peek < len && json.charAt(peek) == ':';
                append(doc, json.substring(i, end), isKey ? COLOR_KEY : COLOR_STRING, false, false);
                i = end; continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < len && Character.isDigit(json.charAt(i + 1)))) {
                int end = i + 1;
                while (end < len && "0123456789.eE+-".indexOf(json.charAt(end)) >= 0) end++;
                append(doc, json.substring(i, end), COLOR_NUMBER, false, false); i = end; continue;
            }
            if (json.startsWith("true",  i)) { append(doc,"true", COLOR_BOOLEAN,true,false); i+=4; continue; }
            if (json.startsWith("false", i)) { append(doc,"false",COLOR_BOOLEAN,true,false); i+=5; continue; }
            if (json.startsWith("null",  i)) { append(doc,"null", COLOR_NULL,   true,false); i+=4; continue; }
            if ("{}[]".indexOf(c) >= 0) { append(doc,String.valueOf(c),COLOR_BRACKET,true, false); i++; continue; }
            if (c == ':' || c == ',')   { append(doc,String.valueOf(c),FG_COLOR,    false,false); i++; continue; }
            append(doc, String.valueOf(c), FG_COLOR, false, false); i++;
        }
        textPane.setCaretPosition(0);
    }

    private void append(StyledDocument doc, String text, Color color, boolean bold, boolean italic) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        try { doc.insertString(doc.getLength(), text, a); } catch (BadLocationException ignored) {}
    }

    // =========================================================================
    // JAVASCRIPT MODE — all new logic
    // =========================================================================

    /** Holds one extracted JS block together with an optional human-readable label. */
    private static class JsBlock {
        final String content;
        final String label;
        JsBlock(String content, String label) { this.content = content; this.label = label; }
    }

    // ── JS render entry point ─────────────────────────────────────────────────

    private void rerenderJs(String body) {
        List<JsBlock> blocks = extractJsBlocks(body);

        if (blocks.isEmpty()) {
            showPlaceholder(
                "No JavaScript found in this response.\n\n" +
                "This mode looks for:\n" +
                " \u2022 <script> inline blocks\n" +
                " \u2022 <script src=\"...\"> external references\n" +
                " \u2022 javascript: URLs in attributes\n" +
                " \u2022 Inline event handlers (onclick, onload, \u2026)\n" +
                " \u2022 Whole-body JS responses\n\n" +
                "Switch to JSON mode to extract JSON blocks."
            );
            statusLabel.setText("No JavaScript found");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            JsBlock block = blocks.get(i);
            // Header comment for each block
            sb.append("// \u2500\u2500\u2500 ");
            if (blocks.size() > 1) sb.append("Block ").append(i + 1).append(" of ").append(blocks.size()).append(" ");
            if (block.label != null) sb.append("[\u202F").append(block.label).append("\u202F]");
            sb.append(" \u2500\u2500\u2500\n");
            sb.append(block.content);
            if (i < blocks.size() - 1) sb.append("\n\n");
        }

        renderJsWithHighlighting(sb.toString());
        int n = blocks.size();
        statusLabel.setText("Found " + n + " JS block" + (n > 1 ? "s" : "")
            + (decodeMode ? "  [decoded]" : ""));
    }

    // ── JS extraction ─────────────────────────────────────────────────────────

    /**
     * Extracts all JavaScript blocks from a response body.
     *
     * Detection order:
     * 1. Whole-body JS (no HTML wrapper, has JS syntax markers).
     * 2. &lt;script&gt; inline blocks.
     * 3. &lt;script src="…"&gt; external references (noted, not fetched).
     * 4. javascript: URLs found anywhere in attributes.
     * 5. Inline event-handler attributes (onclick, onload, …).
     */
    private List<JsBlock> extractJsBlocks(String body) {
        List<JsBlock> results = new ArrayList<>();
        if (body == null || body.isEmpty()) return results;

        String trimmed = body.trim();
        String lower   = trimmed.toLowerCase();

        // ── 1. Whole-body JS heuristic ────────────────────────────────────────
        boolean hasHtmlWrapper = lower.contains("<html") || lower.contains("<!doctype");
        boolean isJsonLike     = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean hasJsMarkers   = trimmed.contains("function") || trimmed.contains("var ")
                              || trimmed.contains("let ")     || trimmed.contains("const ")
                              || trimmed.contains("=>")       || trimmed.contains("require(")
                              || trimmed.contains("module.exports") || trimmed.contains("export ");

        if (!hasHtmlWrapper && !isJsonLike && hasJsMarkers) {
            results.add(new JsBlock(beautifyJs(trimmed), "Full Response Body"));
            return results; // treat entire body as one JS file
        }

        // ── 2 + 3. <script> tags (inline and external) ───────────────────────
        Pattern scriptPattern = Pattern.compile(
            "<script([^>]*)>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher sm = scriptPattern.matcher(body);
        int scriptIdx = 0;
        while (sm.find()) {
            String attrs   = sm.group(1);
            String content = sm.group(2).trim();
            scriptIdx++;

            // External script?
            Matcher srcM = Pattern.compile(
                "src\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE
            ).matcher(attrs);
            if (srcM.find()) {
                String src = firstNonNull(srcM.group(1), srcM.group(2), srcM.group(3));
                results.add(new JsBlock(
                    "// External script — not fetched by this extension\n// src: " + src,
                    "External #" + scriptIdx + " \u2192 " + truncate(src, 55)
                ));
                continue;
            }

            if (!content.isEmpty()) {
                content = stripCdata(content);
                results.add(new JsBlock(beautifyJs(content), "Inline #" + scriptIdx));
            }
        }

        // ── 4. javascript: URLs ───────────────────────────────────────────────
        Pattern jsUrlPattern = Pattern.compile(
            "javascript:\\s*([^\"'\\s>]+)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher jum = jsUrlPattern.matcher(body);
        int urlIdx = 0;
        while (jum.find()) {
            urlIdx++;
            String code = jum.group(1).trim();
            // Skip trivial no-ops
            if (code.equals("void(0)") || code.equals("void(0);") || code.equals("void 0")) continue;
            try { code = URLDecoder.decode(code, StandardCharsets.UTF_8.name()); }
            catch (Exception ignored) {}
            results.add(new JsBlock(
                "// javascript: URL\n" + beautifyJs(code),
                "javascript: URL #" + urlIdx
            ));
        }

        // ── 5. Inline event handlers ──────────────────────────────────────────
        Pattern evtPattern = Pattern.compile(
            "\\bon(click|dblclick|load|unload|submit|reset|change|keydown|keyup|keypress"
            + "|mouseover|mouseout|mouseenter|mouseleave|focus|blur|input|scroll|resize"
            + "|contextmenu|drag|drop|copy|paste|cut|animationend|transitionend)"
            + "\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')",
            Pattern.CASE_INSENSITIVE
        );
        Matcher em = evtPattern.matcher(body);
        int evtIdx = 0;
        while (em.find()) {
            evtIdx++;
            String eventName = em.group(1).toLowerCase();
            String code = firstNonNull(em.group(2), em.group(3), "");
            if (code.trim().isEmpty()) continue;
            results.add(new JsBlock(
                "// Inline event handler: on" + eventName + "\n" + beautifyJs(code.trim()),
                "on" + eventName + " #" + evtIdx
            ));
        }

        return results;
    }

    /**
     * Strips XML CDATA wrappers that are sometimes used inside &lt;script&gt; tags
     * for XHTML compatibility:
     *   //<![CDATA[  …  //]]>
     *   <![CDATA[  …  ]]>
     */
    private String stripCdata(String s) {
        String t = s.trim();
        if (t.startsWith("//<![CDATA[")) {
            t = t.substring("//<![CDATA[".length());
            int end = t.lastIndexOf("//]]>");
            if (end >= 0) t = t.substring(0, end);
        } else if (t.startsWith("<![CDATA[")) {
            t = t.substring("<![CDATA[".length());
            int end = t.lastIndexOf("]]>");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    // ── JS beautifier ─────────────────────────────────────────────────────────

    /**
     * Lightweight JavaScript beautifier — zero dependencies.
     *
     * Handles:
     *  · String literals  — single (' '), double (" "), template (` `)
     *  · Line comments    — // …
     *  · Block comments   — /* … *\/
     *  · Regex literals   — /pattern/flags  (heuristic: context-based)
     *  · Brace indentation — { } increase / decrease indent level
     *  · Semicolons       — ; → newline + current indent
     *  · Commas           — , → collapsed space (preserves readability)
     *  · Whitespace       — all runs collapsed to a single space
     *  · Empty blocks     — {} rendered inline
     */
    private String beautifyJs(String js) {
        if (js == null || js.trim().isEmpty()) return "";

        StringBuilder out = new StringBuilder(js.length() * 2);
        int    indent         = 0;
        int    i              = 0;
        int    len            = js.length();
        char   lastMeaningful = 0; // last non-whitespace char emitted, for regex heuristic

        while (i < len) {
            char c = js.charAt(i);

            // ── Line comment ─────────────────────────────────────────────────
            if (c == '/' && i + 1 < len && js.charAt(i + 1) == '/') {
                ensureNewlinePrefix(out, indent);
                int end = js.indexOf('\n', i);
                if (end < 0) end = len;
                out.append(js, i, end).append('\n');
                i = end + 1;
                skipHorizontalWhitespace(js, i, len); // value not used, just advance past it below
                i = skipHorizontalWs(js, i, len);
                appendJsIndent(out, indent);
                lastMeaningful = 0;
                continue;
            }

            // ── Block comment ────────────────────────────────────────────────
            if (c == '/' && i + 1 < len && js.charAt(i + 1) == '*') {
                ensureNewlinePrefix(out, indent);
                int end = js.indexOf("*/", i + 2);
                if (end < 0) { out.append(js, i, len); i = len; }
                else         { out.append(js, i, end + 2).append('\n'); i = end + 2; }
                i = skipHorizontalWs(js, i, len);
                appendJsIndent(out, indent);
                lastMeaningful = 0;
                continue;
            }

            // ── Regex literal ────────────────────────────────────────────────
            // Only treat '/' as regex when the preceding token makes division impossible.
            if (c == '/' && isRegexContext(lastMeaningful, out)) {
                int end      = i + 1;
                boolean inCC = false;
                while (end < len) {
                    char x = js.charAt(end);
                    if (x == '\\')           { end += 2; continue; }
                    if (x == '[')            { inCC = true;  end++; continue; }
                    if (x == ']')            { inCC = false; end++; continue; }
                    if (!inCC && x == '/')   { end++; break; }
                    if (x == '\n')           break; // unterminated
                    end++;
                }
                while (end < len && Character.isLetter(js.charAt(end))) end++; // flags
                out.append(js, i, Math.min(end, len));
                i = Math.min(end, len);
                lastMeaningful = '/';
                continue;
            }

            // ── String literals ──────────────────────────────────────────────
            if (c == '"' || c == '\'' || c == '`') {
                char quote = c;
                int end = i + 1;
                while (end < len) {
                    char x = js.charAt(end);
                    if (x == '\\') { end += 2; continue; }
                    if (x == quote){ end++; break; }
                    end++;
                }
                out.append(js, i, Math.min(end, len));
                i = Math.min(end, len);
                lastMeaningful = quote;
                continue;
            }

            // ── Open brace ───────────────────────────────────────────────────
            if (c == '{') {
                // Empty block → render {} inline
                int peek = skipAllWs(js, i + 1, len);
                if (peek < len && js.charAt(peek) == '}') {
                    ensureSpace(out);
                    out.append("{}");
                    i = peek + 1;
                    lastMeaningful = '}';
                    continue;
                }
                ensureSpace(out);
                out.append("{\n");
                indent++;
                appendJsIndent(out, indent);
                i++;
                lastMeaningful = '{';
                continue;
            }

            // ── Close brace ──────────────────────────────────────────────────
            if (c == '}') {
                indent = Math.max(0, indent - 1);
                trimTrailingSpaces(out);
                if (outNotEndsWith(out, '\n')) out.append('\n');
                appendJsIndent(out, indent);
                out.append('}');
                int peek = skipAllWs(js, i + 1, len);
                char next = (peek < len) ? js.charAt(peek) : 0;
                // Add a blank line after } unless followed by ; , . ) } or end
                if (next != ';' && next != ',' && next != '.' && next != ')' && next != '}' && next != 0) {
                    out.append('\n');
                    appendJsIndent(out, indent);
                }
                i++;
                lastMeaningful = '}';
                continue;
            }

            // ── Semicolon ────────────────────────────────────────────────────
            if (c == ';') {
                trimTrailingSpaces(out);
                out.append(";\n");
                appendJsIndent(out, indent);
                i = skipAllWs(js, i + 1, len);
                lastMeaningful = ';';
                continue;
            }

            // ── Comma ────────────────────────────────────────────────────────
            if (c == ',') {
                trimTrailingSpaces(out);
                out.append(", ");
                i = skipHorizontalWs(js, i + 1, len);
                lastMeaningful = ',';
                continue;
            }

            // ── Whitespace ───────────────────────────────────────────────────
            if (isWs(c)) {
                i = skipAllWs(js, i, len);
                // Emit a single space only if the current output doesn't already
                // end with whitespace or an open paren / bracket.
                if (out.length() > 0) {
                    char prev = out.charAt(out.length() - 1);
                    if (prev != ' ' && prev != '\n' && prev != '(' && prev != '[') {
                        out.append(' ');
                    }
                }
                continue;
            }

            // ── Everything else ──────────────────────────────────────────────
            out.append(c);
            lastMeaningful = c;
            i++;
        }

        return out.toString().trim();
    }

    /**
     * Returns true when a '/' character should start a regex literal rather
     * than being a division operator.
     * Division is only possible after: identifier, number, ), ], closing quote.
     * After operators, keywords, (, [, {, =, …  it must be a regex.
     */
    private boolean isRegexContext(char last, StringBuilder out) {
        if (last == 0) return true;
        boolean couldBeDivision =
            Character.isLetterOrDigit(last) || last == '_' || last == '$'
            || last == ')' || last == ']' || last == '"' || last == '\'' || last == '`';
        if (!couldBeDivision) return true;

        // Special case: even after a word char, keywords like 'return', 'typeof', etc.
        // are followed by a regex, not a division.
        String s = out.toString();
        int end = s.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_')) start--;
        String word = s.substring(start, end);
        return word.equals("return") || word.equals("typeof")  || word.equals("instanceof")
            || word.equals("in")     || word.equals("delete")  || word.equals("void")
            || word.equals("new")    || word.equals("throw")   || word.equals("case");
    }

    // ── JS beautifier helpers ─────────────────────────────────────────────────

    private void appendJsIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    private void trimTrailingSpaces(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ')
            sb.deleteCharAt(sb.length() - 1);
    }

    private boolean outNotEndsWith(StringBuilder sb, char ch) {
        return sb.length() == 0 || sb.charAt(sb.length() - 1) != ch;
    }

    private void ensureSpace(StringBuilder sb) {
        if (sb.length() == 0) return;
        char last = sb.charAt(sb.length() - 1);
        if (last != ' ' && last != '\n' && last != '\t' && last != '(') sb.append(' ');
    }

    private void ensureNewlinePrefix(StringBuilder sb, int indent) {
        trimTrailingSpaces(sb);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
            appendJsIndent(sb, indent);
        }
    }

    /** Skips only spaces and tabs (not newlines). Returns new index. */
    private int skipHorizontalWs(String s, int i, int len) {
        while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    /** Does nothing — exists so callers read cleanly. */
    private void skipHorizontalWhitespace(String s, int i, int len) { /* intentional no-op */ }

    /** Skips spaces, tabs, newlines, carriage returns. Returns new index. */
    private int skipAllWs(String s, int i, int len) {
        while (i < len && isWs(s.charAt(i))) i++;
        return i;
    }

    private boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // ── JS syntax highlighting ────────────────────────────────────────────────

    /**
     * Renders pre-beautified JavaScript with One-Dark–inspired syntax colours.
     *
     * Token categories and colours:
     *  · Keywords         — purple  (JS_KEYWORD)
     *  · Function calls   — blue    (JS_FUNCTION)
     *  · String literals  — green   (COLOR_STRING)
     *  · Numbers          — orange  (COLOR_NUMBER)
     *  · Regex literals   — amber   (JS_REGEX)
     *  · Line/block cmt   — grey italic (COMMENT_COLOR)
     *  · Operators        — cyan    (JS_OPERATOR)
     *  · Brackets/braces  — white bold (COLOR_BRACKET)
     *  · Everything else  — default (FG_COLOR)
     */
    private void renderJsWithHighlighting(String js) {
        StyledDocument doc = textPane.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException e) { return; }

        int i = 0, len = js.length();

        while (i < len) {
            char c = js.charAt(i);

            // Line comment
            if (c == '/' && i + 1 < len && js.charAt(i + 1) == '/') {
                int end = js.indexOf('\n', i);
                if (end < 0) end = len;
                append(doc, js.substring(i, end), COMMENT_COLOR, false, true);
                i = end;
                continue;
            }

            // Block comment
            if (c == '/' && i + 1 < len && js.charAt(i + 1) == '*') {
                int end = js.indexOf("*/", i + 2);
                if (end < 0) { append(doc, js.substring(i),         COMMENT_COLOR, false, true); i = len; }
                else         { append(doc, js.substring(i, end + 2), COMMENT_COLOR, false, true); i = end + 2; }
                continue;
            }

            // Regex literals — detected by a simple /…/ span check
            if (c == '/') {
                int end = i + 1;
                boolean inCC = false, valid = false;
                while (end < len) {
                    char x = js.charAt(end);
                    if (x == '\\')         { end += 2; continue; }
                    if (x == '[')          { inCC = true;  end++; continue; }
                    if (x == ']')          { inCC = false; end++; continue; }
                    if (!inCC && x == '/') { end++; valid = true; break; }
                    if (x == '\n')         break;
                    end++;
                }
                if (valid) {
                    while (end < len && Character.isLetter(js.charAt(end))) end++; // flags
                    append(doc, js.substring(i, Math.min(end, len)), JS_REGEX, false, false);
                    i = Math.min(end, len);
                } else {
                    append(doc, "/", JS_OPERATOR, false, false);
                    i++;
                }
                continue;
            }

            // String literals (single, double, template)
            if (c == '"' || c == '\'' || c == '`') {
                char quote = c;
                int end = i + 1;
                while (end < len) {
                    char x = js.charAt(end);
                    if (x == '\\') { end += 2; continue; }
                    if (x == quote){ end++; break; }
                    end++;
                }
                append(doc, js.substring(i, Math.min(end, len)), COLOR_STRING, false, false);
                i = Math.min(end, len);
                continue;
            }

            // Numbers (decimal, hex 0x…, binary 0b…, octal 0o…, BigInt …n)
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(js.charAt(i + 1)))) {
                int end = i + 1;
                while (end < len && "0123456789.eExXbBoOabcdefABCDEF_n".indexOf(js.charAt(end)) >= 0) end++;
                append(doc, js.substring(i, end), COLOR_NUMBER, false, false);
                i = end;
                continue;
            }

            // Identifiers, keywords, function calls
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int end = i + 1;
                while (end < len && (Character.isLetterOrDigit(js.charAt(end))
                                     || js.charAt(end) == '_' || js.charAt(end) == '$')) end++;
                String word = js.substring(i, end);

                // Look ahead for '(' to classify as a function call
                int peek = end;
                while (peek < len && js.charAt(peek) == ' ') peek++;
                boolean isCall = (peek < len && js.charAt(peek) == '(');

                if (JS_KEYWORDS.contains(word)) {
                    append(doc, word, JS_KEYWORD, true, false);
                } else if (isCall) {
                    append(doc, word, JS_FUNCTION, false, false);
                } else {
                    append(doc, word, FG_COLOR, false, false);
                }
                i = end;
                continue;
            }

            // Brackets, braces, parentheses
            if ("{}[]()".indexOf(c) >= 0) {
                append(doc, String.valueOf(c), COLOR_BRACKET, true, false);
                i++; continue;
            }

            // Operators
            if ("=+\\-*<>!&|^~%?:".indexOf(c) >= 0) {
                append(doc, String.valueOf(c), JS_OPERATOR, false, false);
                i++; continue;
            }

            // Everything else (whitespace, punctuation, …)
            append(doc, String.valueOf(c), FG_COLOR, false, false);
            i++;
        }

        textPane.setCaretPosition(0);
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    // ── Search helpers ────────────────────────────────────────────────────────

    private void runSearch() {
        clearSearchHighlights();
        String query = searchField.getText();
        if (query == null || query.isEmpty()) { matchLabel.setText(""); return; }

        String body = textPane.getText();
        searchMatches.clear();
        searchIndex = -1;

        int idx = 0;
        while ((idx = body.indexOf(query, idx)) >= 0) {
            searchMatches.add(new int[]{idx, idx + query.length()});
            idx += query.length();
        }

        if (searchMatches.isEmpty()) {
            matchLabel.setText("No matches");
            matchLabel.setForeground(COLOR_NULL);
            return;
        }

        // Highlight all matches
        Highlighter h = textPane.getHighlighter();
        for (int[] m : searchMatches) {
            try {
                h.addHighlight(m[0], m[1],
                    new DefaultHighlighter.DefaultHighlightPainter(SEARCH_HIGHLIGHT));
            } catch (BadLocationException ignored) {}
        }

        navigateMatch(+1); // jump to first
    }

    private void navigateMatch(int direction) {
        if (searchMatches.isEmpty()) return;
        searchIndex = (searchIndex + direction + searchMatches.size()) % searchMatches.size();
        matchLabel.setText((searchIndex + 1) + " / " + searchMatches.size());
        matchLabel.setForeground(COMMENT_COLOR);

        int[] m = searchMatches.get(searchIndex);
        // Re-highlight: remove all, re-add all as amber, current one as red
        Highlighter h = textPane.getHighlighter();
        h.removeAllHighlights();
        for (int i = 0; i < searchMatches.size(); i++) {
            int[] mm = searchMatches.get(i);
            Color c = (i == searchIndex) ? SEARCH_CURRENT : SEARCH_HIGHLIGHT;
            try {
                h.addHighlight(mm[0], mm[1], new DefaultHighlighter.DefaultHighlightPainter(c));
            } catch (BadLocationException ignored) {}
        }

        // Scroll current match into view
        try {
            Rectangle2D r = textPane.modelToView2D(m[0]);
            if (r != null) textPane.scrollRectToVisible(r.getBounds());
        } catch (BadLocationException ignored) {}
        textPane.setCaretPosition(m[0]);
    }

    private void clearSearchHighlights() {
        textPane.getHighlighter().removeAllHighlights();
        searchMatches.clear();
        searchIndex = -1;
        matchLabel.setText("");
        matchLabel.setForeground(COMMENT_COLOR);
    }
    private void applyFontSize() {
            Font f = pickMonoFont(fontSize);
            textPane.setFont(f);
            scrollPane.getRowHeader().getView().setFont(f); // update line numbers too
            if (lastRawBody != null) rerender(lastRawBody);
    }

    private void showPlaceholder(String msg) {
        StyledDocument doc = textPane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, COMMENT_COLOR);
            StyleConstants.setItalic(a, true);
            doc.insertString(0, "  " + msg.replace("\n", "\n  "), a);
        } catch (BadLocationException ignored) {}
    }

    private JButton makeButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setForeground(Color.WHITE);
        btn.setBackground(BTN_INACTIVE);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private static Font pickMonoFont(int size) {
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "Courier New"}) {
            Font f = new Font(name, Font.PLAIN, size);
            if (f.getFamily().equalsIgnoreCase(name)) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** Returns the first non-null, non-empty string from the arguments, or fallback. */
    private static String firstNonNull(String... candidates) {
        for (String s : candidates) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "…";
    }
}