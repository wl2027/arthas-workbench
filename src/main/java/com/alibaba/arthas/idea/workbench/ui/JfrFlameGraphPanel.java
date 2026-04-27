package com.alibaba.arthas.idea.workbench.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import org.eclipse.jifa.jfr.enums.Unit;

/**
 * 更贴近 Jifa Web JFR 页面风格的 Swing flame graph。
 */
final class JfrFlameGraphPanel extends JComponent {

    private static final int BAR_HEIGHT = 24;
    private static final int HORIZONTAL_PADDING = 0;
    private static final int VERTICAL_PADDING = 0;
    private static final int X_GAP = 1;
    private static final double X_GAP_THRESHOLD = 0.01d;
    private static final int Y_GAP = 1;
    private static final int BOTTOM_GAP = 5;
    private static final int TEXT_GAP = 6;
    private static final int TEXT_THRESHOLD = 30;
    private static final Color BACKGROUND = Color.WHITE;
    private static final Color ROOT_BACKGROUND = new Color(0x53, 0x7E, 0x8B);
    private static final Color ROOT_FOREGROUND = Color.WHITE;
    private static final Color ZERO_BACKGROUND = new Color(0xC5, 0xC8, 0xD3);
    private static final Color ZERO_FOREGROUND = Color.BLACK;
    private static final Color SELECTED_BORDER = Color.BLACK;
    private static final Color[] PALETTE_BACKGROUNDS = {
        new Color(0x76, 0x1D, 0x96),
        new Color(0xC1, 0x25, 0x61),
        new Color(0xFE, 0xC9, 0x1B),
        new Color(0x3F, 0x73, 0x50),
        new Color(0x40, 0x81, 0x18),
        new Color(0x3E, 0xA9, 0xDA),
        new Color(0x9F, 0xB0, 0x36),
        new Color(0xB6, 0x71, 0xC1),
        new Color(0xFA, 0xA9, 0x38)
    };
    private static final Color[] PALETTE_FOREGROUNDS = {
        Color.WHITE,
        Color.WHITE,
        Color.BLACK,
        Color.WHITE,
        Color.WHITE,
        Color.BLACK,
        Color.WHITE,
        Color.WHITE,
        Color.BLACK
    };

    private final List<LayoutFrame> layoutFrames = new ArrayList<>();
    private final Consumer<JfrFlameGraphTreeBuilder.FlameGraphNode> selectionListener;
    private final Font frameFont = new Font("Menlo", Font.PLAIN, 12);
    private final Font rootFont = new Font("Menlo", Font.BOLD, 14);

    private JfrFlameGraphTreeBuilder.FlameGraphNode root =
            new JfrFlameGraphTreeBuilder.FlameGraphNode("All Threads", "");
    private JfrFlameGraphTreeBuilder.FlameGraphNode selectedNode;
    private String dimensionKey = "CPU Time";
    private String rootText = "Total CPU Time: 0";
    private Unit unit = Unit.COUNT;
    private long totalWeight;

    JfrFlameGraphPanel(Consumer<JfrFlameGraphTreeBuilder.FlameGraphNode> selectionListener) {
        this.selectionListener = selectionListener;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                LayoutFrame frame = frameAt(event.getX(), event.getY());
                selectedNode = frame == null ? null : frame.node();
                if (frame != null) {
                    selectionListener.accept(frame.node());
                }
                repaint();
            }
        });
    }

    void setData(JfrFlameGraphTreeBuilder.FlameGraphNode root, String dimensionKey, Unit unit, long totalWeight) {
        this.root = root == null ? new JfrFlameGraphTreeBuilder.FlameGraphNode("All Threads", "") : root;
        this.dimensionKey = dimensionKey == null || dimensionKey.isBlank() ? "CPU Time" : dimensionKey;
        this.unit = unit == null ? Unit.COUNT : unit;
        this.totalWeight = totalWeight <= 0 ? this.root.getWeight() : totalWeight;
        this.rootText =
                "Total " + this.dimensionKey + ": " + JifaUiFormatters.formatJfrValue(this.unit, this.totalWeight);
        this.selectedNode = null;
        revalidate();
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        LayoutFrame frame = frameAt(event.getX(), event.getY());
        if (frame == null) {
            return null;
        }
        JfrFlameGraphTreeBuilder.FlameGraphNode node = frame.node();
        long total = Math.max(1L, totalWeight);
        double share = node.getWeight() * 100.0 / total;
        if (node == root) {
            return "<html><b>" + escape(rootText) + "</b><br/>Share: 100.00%</html>";
        }
        return "<html><b>" + escape(node.getName()) + "</b><br/>"
                + "Package: " + escape(node.getPackageName().isBlank() ? "-" : node.getPackageName()) + "<br/>"
                + "Total: " + JifaUiFormatters.formatJfrValue(unit, node.getWeight()) + "<br/>"
                + "Self: " + JifaUiFormatters.formatJfrValue(unit, node.getSelfWeight()) + "<br/>"
                + "Share: " + JifaUiFormatters.formatPercent(share) + "</html>";
    }

    @Override
    public Dimension getPreferredSize() {
        int depth = Math.max(1, maxDepth(root, 0) + 1);
        int height = VERTICAL_PADDING * 2 + depth * BAR_HEIGHT + Math.max(0, depth - 1) * Y_GAP + BOTTOM_GAP;
        return new Dimension(960, Math.max(320, height));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            layoutFrames.clear();
            if (root == null || root.getWeight() <= 0 || getWidth() <= HORIZONTAL_PADDING * 2) {
                return;
            }
            int contentWidth = Math.max(1, getWidth() - HORIZONTAL_PADDING * 2);
            int x = HORIZONTAL_PADDING;
            int y = VERTICAL_PADDING;
            paintNode(g2, root, x, y, contentWidth);
        } finally {
            g2.dispose();
        }
    }

    private void paintNode(Graphics2D g2, JfrFlameGraphTreeBuilder.FlameGraphNode node, int x, int y, int width) {
        if (node == null || width <= 0 || node.getWeight() <= 0) {
            return;
        }
        Rectangle bounds = new Rectangle(x, y, width, BAR_HEIGHT);
        layoutFrames.add(new LayoutFrame(bounds, node));

        ColorPair colors = colorsOf(node);
        g2.setColor(colors.background());
        g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        if (node == selectedNode) {
            g2.setColor(SELECTED_BORDER);
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawRect(bounds.x, bounds.y, Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
        }
        drawLabel(g2, node, bounds, colors.foreground());

        List<JfrFlameGraphTreeBuilder.FlameGraphNode> children = node.sortedChildren();
        if (children.isEmpty()) {
            return;
        }

        int childY = y + BAR_HEIGHT + Y_GAP;
        int childGap = X_GAP;
        int spaces = Math.max(0, children.size() - 1);
        int leftWidth = width;
        if (width <= 0 || (spaces * childGap * 1.0d) / Math.max(1, width) > X_GAP_THRESHOLD) {
            childGap = 0;
        } else {
            leftWidth -= spaces * childGap;
        }

        int nextX = x;
        int endX = x + width;
        for (int i = 0; i < children.size(); i++) {
            JfrFlameGraphTreeBuilder.FlameGraphNode child = children.get(i);
            int childWidth;
            if (i == children.size() - 1 && node.getSelfWeight() == 0) {
                childWidth = endX - nextX;
            } else {
                childWidth = (int) Math.round(leftWidth * (child.getWeight() * 1.0d / node.getWeight()));
            }
            if (childWidth <= 0) {
                continue;
            }
            paintNode(g2, child, nextX, childY, childWidth);
            nextX += childWidth + childGap;
        }
    }

    private void drawLabel(
            Graphics2D g2, JfrFlameGraphTreeBuilder.FlameGraphNode node, Rectangle bounds, Color foreground) {
        String label = node == root ? rootText : node.getName();
        if (bounds.width <= TEXT_THRESHOLD || label == null || label.isBlank()) {
            return;
        }
        Font font = node == root ? rootFont : frameFont;
        g2.setFont(font);
        g2.setColor(foreground);
        g2.setBackground(Color.WHITE);
        g2.setPaintMode();
        FontMetrics metrics = g2.getFontMetrics(font);
        String visibleLabel = compactLabel(label, metrics, bounds.width - 2 * TEXT_GAP);
        if (visibleLabel == null || visibleLabel.isBlank()) {
            return;
        }
        int baseline = bounds.y + ((bounds.height - metrics.getHeight()) / 2) + metrics.getAscent();
        g2.drawString(visibleLabel, bounds.x + TEXT_GAP, baseline);
    }

    private String compactLabel(String label, FontMetrics metrics, int maxWidth) {
        if (maxWidth <= 0) {
            return null;
        }
        if (metrics.stringWidth(label) <= maxWidth) {
            return label;
        }
        String suffix = "...";
        int suffixWidth = metrics.stringWidth(suffix);
        for (int length = label.length() - 1; length > 0; length--) {
            String candidate = label.substring(0, length) + suffix;
            if (metrics.stringWidth(candidate) <= maxWidth || maxWidth <= suffixWidth) {
                return candidate;
            }
        }
        return suffix;
    }

    private ColorPair colorsOf(JfrFlameGraphTreeBuilder.FlameGraphNode node) {
        if (node == root) {
            return new ColorPair(ROOT_BACKGROUND, ROOT_FOREGROUND);
        }
        String packageName = node.getPackageName();
        if (packageName == null || packageName.isBlank()) {
            return new ColorPair(ZERO_BACKGROUND, ZERO_FOREGROUND);
        }
        if (packageName.startsWith("java") || packageName.startsWith("jdk") || packageName.startsWith("sun")) {
            return new ColorPair(ZERO_BACKGROUND, ZERO_FOREGROUND);
        }
        int hash = hashPackage(packageName);
        if (hash == 0) {
            return new ColorPair(ZERO_BACKGROUND, ZERO_FOREGROUND);
        }
        int paletteIndex = Math.floorMod(hash, PALETTE_BACKGROUNDS.length);
        return new ColorPair(PALETTE_BACKGROUNDS[paletteIndex], PALETTE_FOREGROUNDS[paletteIndex]);
    }

    private int hashPackage(String packageName) {
        int hash = 0;
        for (int i = 0; i < packageName.length(); i++) {
            hash = 31 * hash + (packageName.charAt(i) & 0xFF);
        }
        return hash;
    }

    private int maxDepth(JfrFlameGraphTreeBuilder.FlameGraphNode node, int current) {
        int depth = current;
        for (JfrFlameGraphTreeBuilder.FlameGraphNode child : node.getChildren()) {
            depth = Math.max(depth, maxDepth(child, current + 1));
        }
        return depth;
    }

    private LayoutFrame frameAt(int x, int y) {
        for (int i = layoutFrames.size() - 1; i >= 0; i--) {
            LayoutFrame frame = layoutFrames.get(i);
            if (frame.bounds().contains(x, y)) {
                return frame;
            }
        }
        return null;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record LayoutFrame(Rectangle bounds, JfrFlameGraphTreeBuilder.FlameGraphNode node) {}

    private record ColorPair(Color background, Color foreground) {}
}
