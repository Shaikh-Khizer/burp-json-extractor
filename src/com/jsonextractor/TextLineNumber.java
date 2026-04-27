package com.jsonextractor;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/**
 * A JComponent that paints line numbers in the left gutter of a JTextPane.
 * Attach it as the row-header of the enclosing JScrollPane.
 *
 * Fix vs original: modelToView2D() can return null (e.g. before the component
 * is laid out); we null-check before calling .getBounds().
 */
public class TextLineNumber extends JComponent implements CaretListener, DocumentListener {

    private static final Color BG = new Color(0x21252B);
    private static final Color FG = new Color(0x495162);

    private final JTextPane textPane;

    public TextLineNumber(JTextPane textPane) {
        this.textPane = textPane;
        setFont(textPane.getFont());
        setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 10));
        setBackground(BG);
        setForeground(FG);
        setOpaque(true);
        textPane.getDocument().addDocumentListener(this);
        textPane.addCaretListener(this);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());

        FontMetrics fm = g.getFontMetrics(getFont());
        Rectangle visible = textPane.getVisibleRect();

        // viewToModel2D: pixel → document offset
        int startOffset = textPane.viewToModel2D(new Point(0, visible.y));
        int endOffset   = textPane.viewToModel2D(new Point(0, visible.y + visible.height));

        Element root      = textPane.getDocument().getDefaultRootElement();
        int     startLine = root.getElementIndex(startOffset);
        int     endLine   = root.getElementIndex(endOffset);

        g.setColor(FG);
        g.setFont(getFont());

        for (int line = startLine; line <= endLine; line++) {
            Element lineEl = root.getElement(line);
            if (lineEl == null) continue;
            try {
                // modelToView2D can return null before layout is complete — guard it
                Rectangle2D r2d = textPane.modelToView2D(lineEl.getStartOffset());
                if (r2d == null) continue;
                Rectangle r = r2d.getBounds();

                String lineNum = String.valueOf(line + 1);
                int x = getWidth() - fm.stringWidth(lineNum) - 6;
                g.drawString(lineNum, x, r.y + fm.getAscent());
            } catch (BadLocationException ignored) {}
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int lines  = textPane.getDocument().getDefaultRootElement().getElementCount();
        int digits = Math.max(3, String.valueOf(lines).length());
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.charWidth('0') * digits + 16, textPane.getHeight());
    }

    // ── Listeners — repaint whenever caret or document changes ───────────────
    @Override public void caretUpdate(CaretEvent e)        { repaint(); }
    @Override public void insertUpdate(DocumentEvent e)    { repaint(); revalidate(); }
    @Override public void removeUpdate(DocumentEvent e)    { repaint(); revalidate(); }
    @Override public void changedUpdate(DocumentEvent e)   { repaint(); revalidate(); }
}
