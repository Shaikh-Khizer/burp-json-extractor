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
import java.util.List;

/**
 * "JSON" tab in Burp response editor.
 *
 * Features
 * - Extracts and pretty-prints JSON blocks from any response body.
 * - [Original | Decoded] toggle: URL-decodes then HTML-decodes the entire body text.
 * - Copy button.
 * - Syntax highlighting (One Dark).
 * - Line numbers.
 * - Zero third-party dependencies.
 */
public class JsonExtractorTab implements ExtensionProvidedHttpResponseEditor {

    // ── Colours ──────────────────────────────────────────────────────────────
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

    // ── Swing ────────────────────────────────────────────────────────────────
    private final JPanel      mainPanel;
    private final JTextPane   textPane;
    private final JScrollPane scrollPane;
    private final JLabel      statusLabel;
    private final JButton     decodeBtn;

    // ── State ────────────────────────────────────────────────────────────────
    @SuppressWarnings("unused")
    private final MontoyaApi api;
    private HttpResponse currentResponse;
    private String  lastRawBody      = null;   // raw response body, preserved
    private boolean decodeMode       = false;

    // ─────────────────────────────────────────────────────────────────────────

    public JsonExtractorTab(MontoyaApi api) {
        this.api = api;

        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(BG_COLOR);

        // Text pane — init before toolbar lambdas capture it
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(BG_COLOR);
        textPane.setForeground(FG_COLOR);
        textPane.setCaretColor(Color.WHITE);
        textPane.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        textPane.setFont(pickMonoFont());

        scrollPane = new JScrollPane(textPane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_COLOR);
        scrollPane.setRowHeaderView(new TextLineNumber(textPane));
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.setBackground(TOOLBAR_COLOR);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        statusLabel = new JLabel("No response loaded");
        statusLabel.setForeground(COMMENT_COLOR);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));

        JButton copyBtn = makeButton("Copy JSON");
        copyBtn.addActionListener(e -> {
            String text = textPane.getText();
            if (text != null && !text.isEmpty()) {
                Toolkit.getDefaultToolkit()
                       .getSystemClipboard()
                       .setContents(new StringSelection(text), null);
                statusLabel.setText("Copied!");
            }
        });

        decodeBtn = makeButton("Original");
        decodeBtn.setToolTipText("Toggle URL + HTML decoding");
        decodeBtn.addActionListener(e -> {
            decodeMode = !decodeMode;
            decodeBtn.setText(decodeMode ? "Decoded" : "Original");
            decodeBtn.setBackground(decodeMode ? BTN_ACTIVE : BTN_INACTIVE);
            if (lastRawBody != null) {
                rerender(lastRawBody);
            }
        });

        toolbar.add(statusLabel);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(copyBtn);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(decodeBtn);

        mainPanel.add(toolbar,    BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
    }

    // ── Montoya API ──────────────────────────────────────────────────────────

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
     * Core render call — optionally decodes the body first, then extracts
     * and pretty-prints JSON blocks from it.
     */
    private void rerender(String rawBody) {
        // If decode mode: URL-decode then HTML-decode the whole body before
        // JSON extraction. This handles cases where the server sends JSON
        // that is URL-encoded or HTML-entity-encoded at the transport level.
        String body = decodeMode ? fullyDecode(rawBody) : rawBody;

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

    @Override public HttpResponse getResponse()                   { return currentResponse; }
    @Override public boolean isEnabledFor(HttpRequestResponse r)  { return true; }
    @Override public String    caption()                          { return "JSON"; }
    @Override public Component uiComponent()                      { return mainPanel; }
    @Override public boolean   isModified()                       { return false; }

    @Override
    public Selection selectedData() {
        String sel = textPane.getSelectedText();
        if (sel == null || sel.isEmpty()) return null;
        return Selection.selection(ByteArray.byteArray(sel.getBytes()));
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    /**
     * Step 1: URL-decode the whole string (%2F -> /, + -> space, etc.)
     * Step 2: HTML-entity-decode the result (&amp; -> &, &lt; -> <, &#123; -> { etc.)
     *
     * Decoding is applied to the RAW body BEFORE JSON extraction so that
     * encoded JSON is still parsed correctly.
     */
    private String fullyDecode(String s) {
        // URL decode
        String result;
        try {
            result = URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            result = s;
        }
        // HTML entity decode
        result = htmlEntityDecode(result);
        return result;
    }

    private String htmlEntityDecode(String s) {
        if (!s.contains("&")) return s;

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            // Find the semicolon — limit search to 12 chars to avoid runaway
            int semi = -1;
            for (int j = i + 1; j < s.length() && j < i + 12; j++) {
                if (s.charAt(j) == ';') { semi = j; break; }
            }
            if (semi == -1) {
                // No semicolon found nearby — treat & as literal
                out.append(c); i++; continue;
            }
            String entity = s.substring(i, semi + 1); // e.g. "&amp;" or "&#65;" or "&#x41;"
            String decoded = decodeHtmlEntity(entity);
            if (decoded != null) {
                out.append(decoded);
                i = semi + 1;
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }

    private String decodeHtmlEntity(String entity) {
        // Numeric decimal: &#65; -> 'A'
        if (entity.startsWith("&#") && !entity.startsWith("&#x") && entity.endsWith(";")) {
            try {
                int code = Integer.parseInt(entity, 2, entity.length() - 1, 10);
                return new String(Character.toChars(code));
            } catch (NumberFormatException ignored) {}
        }
        // Numeric hex: &#x41; -> 'A'
        if (entity.startsWith("&#x") && entity.endsWith(";")) {
            try {
                int code = Integer.parseInt(entity, 3, entity.length() - 1, 16);
                return new String(Character.toChars(code));
            } catch (NumberFormatException ignored) {}
        }
        // Named entities
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

    // ── Syntax highlighting ───────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static Font pickMonoFont() {
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "Courier New"}) {
            Font f = new Font(name, Font.PLAIN, 12);
            if (f.getFamily().equalsIgnoreCase(name)) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }
}