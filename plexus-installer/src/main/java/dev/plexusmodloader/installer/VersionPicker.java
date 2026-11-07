package dev.plexusmodloader.installer;

import dev.plexusmodloader.installer.VersionFilter.Row;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Minecraft version control of the installer window: a search box above a list of the versions grouped by series,
 * and a row of shortcuts to the default and the recently installed versions.
 *
 * <ul>
 *   <li>Typing narrows the list at once and selects the best match; Escape clears the search.
 *   <li>Up and Down (in the search box or the list) move through the versions and skip the series headings; Enter in
 *       the search box moves to the list, and Enter in the list calls the action given to {@link #onEnterInList}.
 *   <li>A click on a series heading folds or unfolds that series while the search is empty. The series of the selected
 *       version stays open, so the selection is never hidden.
 *   <li>When nothing matches, a line says so instead of an empty list.
 * </ul>
 *
 * <p>What is listed, in which order and what a text matches is decided by {@link VersionFilter}; this class only draws
 * it.
 *
 * <p>All of its methods run on the Swing event thread.
 */
final class VersionPicker {

    private static final int VISIBLE_ROWS = 6;
    private static final String LIST = "list";
    private static final String NONE = "none";
    private static final String ENTER_IN_LIST = "versionEnter";

    private final VersionFilter filter;
    private final Map<String, String> text;
    private final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final JTextField search;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JTextArea none = new JTextArea();
    private final CardLayout cards = new CardLayout();
    private final JPanel results = new JPanel(cards);
    private final Set<String> folded = new HashSet<>();
    private Runnable onEnter = () -> {};

    /**
     * @param filter the offered versions and their order
     * @param text the window's strings: {@code versionSearch}, {@code versionSeries}, {@code versionDefault}, {@code
     *     versionNone}, {@code shortcutDefault} and {@code shortcutRecent}
     */
    VersionPicker(VersionFilter filter, Map<String, String> text) {
        this(filter, text, List.of());
    }

    /**
     * @param filter the offered versions and their order
     * @param text the window's strings, as for {@link #VersionPicker(VersionFilter, Map)}
     * @param recent the versions installed lately, newest first; each gets a shortcut button after the default's
     */
    VersionPicker(VersionFilter filter, Map<String, String> text, List<String> recent) {
        this.filter = filter;
        this.text = text;
        String hint = text.get("versionSearch");
        Color hintColor = disabledColor();
        search = new JTextField() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getText().isEmpty()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(hintColor);
                    g.setFont(getFont());
                    FontMetrics metrics = g.getFontMetrics();
                    Insets insets = getInsets();
                    int inner = getHeight() - insets.top - insets.bottom;
                    g.drawString(
                            hint, insets.left, insets.top + (inner - metrics.getHeight()) / 2 + metrics.getAscent());
                    g.dispose();
                }
            }
        };
        search.setToolTipText(hint);
        search.getAccessibleContext().setAccessibleName(hint);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refilter();
            }
        });
        // Enter hands over to the list (the choice is made), it does not install by accident.
        search.addActionListener(event -> {
            if (selectedVersion().isPresent()) {
                list.requestFocusInWindow();
            }
        });
        bind(search, KeyEvent.VK_DOWN, "versionNext", () -> move(1));
        bind(search, KeyEvent.VK_UP, "versionPrevious", () -> move(-1));
        bind(search, KeyEvent.VK_ESCAPE, "versionClear", () -> search.setText(""));

        list.setSelectionModel(new VersionSelection());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(VISIBLE_ROWS);
        list.setCellRenderer(new RowRenderer());
        list.setPrototypeCellValue(new Row.Version("26.1.1", true));
        list.getAccessibleContext().setAccessibleName(text.get("minecraft"));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0
                        && list.getCellBounds(index, index).contains(event.getPoint())
                        && model.get(index) instanceof Row.Header header) {
                    toggleSeries(header.series());
                }
            }
        });
        bind(list, KeyEvent.VK_ENTER, ENTER_IN_LIST, () -> onEnter.run());

        none.setEditable(false);
        none.setFocusable(false);
        none.setLineWrap(true);
        none.setWrapStyleWord(true);
        none.setOpaque(false);
        none.setForeground(disabledColor());
        none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        none.getAccessibleContext().setAccessibleName(text.get("minecraft"));

        JScrollPane listPane = new JScrollPane(list);
        JPanel nonePane = new JPanel(new BorderLayout());
        nonePane.setBorder(listPane.getBorder());
        nonePane.add(none, BorderLayout.NORTH);
        results.add(listPane, LIST);
        results.add(nonePane, NONE);

        panel.add(search, BorderLayout.NORTH);
        panel.add(results, BorderLayout.CENTER);
        panel.add(shortcuts(recent), BorderLayout.SOUTH);
        // With an empty search the best match is the default version, so it starts selected.
        refilter();
    }

    /** The component to put into the window. */
    JComponent component() {
        return panel;
    }

    /** The action Enter runs while the list has the focus; the window installs the chosen version with it. */
    void onEnterInList(Runnable action) {
        onEnter = action;
    }

    /** The version the player chose, or empty while the search matches none. */
    Optional<String> selectedVersion() {
        return list.getSelectedValue() instanceof Row.Version version ? Optional.of(version.id()) : Optional.empty();
    }

    /**
     * Selects {@code version}. The search is cleared when it hides that version, and the series of it is unfolded. A
     * version not offered is ignored.
     */
    void select(String version) {
        if (!filter.versions().contains(version)) {
            return;
        }
        if (folded.remove(VersionFilter.series(version))) {
            refilter();
        }
        if (!selectIfListed(version) && !search.getText().isEmpty()) {
            search.setText("");
            selectIfListed(version);
        }
    }

    /** The text in the search box. */
    String query() {
        return search.getText();
    }

    /** Puts {@code query} into the search box, which narrows the list. */
    void setQuery(String query) {
        search.setText(query);
    }

    /**
     * Folds the series {@code series} when it is open, or unfolds it. Nothing happens while a search is typed (the
     * search shows every match), and a series that holds the selected version stays open.
     */
    void toggleSeries(String series) {
        if (!search.getText().isBlank()) {
            return;
        }
        if (folded.remove(series)) {
            refilter();
            return;
        }
        boolean holdsSelection = selectedVersion()
                .map(version -> VersionFilter.series(version).equals(series))
                .orElse(false);
        if (!holdsSelection) {
            folded.add(series);
            refilter();
        }
    }

    private boolean selectIfListed(String version) {
        for (int index = 0; index < model.size(); index++) {
            if (model.get(index) instanceof Row.Version row && row.id().equals(version)) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                return true;
            }
        }
        return false;
    }

    /** Lists what the search matches and keeps the selection when it still matches, otherwise selects the best match. */
    private void refilter() {
        String previous = selectedVersion().orElse(null);
        String query = search.getText();
        List<Row> rows = foldedRows(filter.rows(query), query.isBlank());
        list.clearSelection();
        model.clear();
        rows.forEach(model::addElement);
        if (rows.isEmpty()) {
            none.setText(text.get("versionNone")
                    .formatted(query.strip(), filter.versions().size()));
            cards.show(results, NONE);
            return;
        }
        cards.show(results, LIST);
        // A search that names a version in full selects it, even if another listed version stays chosen.
        if (filter.exactMatch(query).map(this::selectIfListed).orElse(false)) {
            return;
        }
        if (previous != null && selectIfListed(previous)) {
            return;
        }
        // The best match may be folded away; then the first version shown is selected.
        if (!filter.firstMatch(query).map(this::selectIfListed).orElse(false)) {
            int first = nextVersion(-1, 1);
            if (first >= 0) {
                list.setSelectedIndex(first);
            }
        }
    }

    /** The rows with the versions of folded series left out (the headings stay, with their count). */
    private List<Row> foldedRows(List<Row> rows, boolean applies) {
        if (!applies || folded.isEmpty()) {
            return rows;
        }
        List<Row> shown = new ArrayList<>();
        boolean hidden = false;
        for (Row row : rows) {
            if (row instanceof Row.Header header) {
                hidden = folded.contains(header.series());
                shown.add(row);
            } else if (!hidden) {
                shown.add(row);
            }
        }
        return shown;
    }

    /** Moves the selection to the next ({@code 1}) or previous ({@code -1}) version, skipping headings. */
    void move(int step) {
        int target = nextVersion(list.getSelectedIndex(), step);
        if (target >= 0) {
            list.setSelectedIndex(target);
            list.ensureIndexIsVisible(target);
        }
    }

    /** The index of the first version row after {@code from} in the direction of {@code step}, or -1. */
    private int nextVersion(int from, int step) {
        for (int index = from + step; index >= 0 && index < model.size(); index += step) {
            if (model.get(index) instanceof Row.Version) {
                return index;
            }
        }
        return -1;
    }

    /** The row under the default version's button and the recent versions' buttons; each selects its version. */
    private JPanel shortcuts(List<String> recent) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        String defaultVersion = filter.defaultVersion();
        row.add(shortcut(defaultVersion, text.get("shortcutDefault").formatted(defaultVersion)));
        List<String> others = recent.stream()
                .filter(filter.versions()::contains)
                .filter(version -> !version.equals(defaultVersion))
                .distinct()
                .limit(3)
                .toList();
        if (!others.isEmpty()) {
            row.add(new JLabel(text.get("shortcutRecent")));
            others.forEach(version -> row.add(shortcut(version, version)));
        }
        return row;
    }

    private JButton shortcut(String version, String label) {
        JButton button = new JButton(label);
        button.getAccessibleContext().setAccessibleName(label);
        button.addActionListener(event -> {
            setQuery("");
            select(version);
        });
        return button;
    }

    private static void bind(JComponent component, int keyCode, String name, Runnable action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static Color disabledColor() {
        Color color = UIManager.getColor("Label.disabledForeground");
        return color == null ? Color.GRAY : color;
    }

    /**
     * A selection that never rests on a series heading: it moves on to the next version in the direction of travel. A
     * mouse click on a heading selects nothing, because that click folds or unfolds the series.
     */
    private final class VersionSelection extends DefaultListSelectionModel {

        private static final long serialVersionUID = 1L;

        @Override
        public void setSelectionInterval(int anchor, int lead) {
            int target = lead;
            if (target >= 0 && target < model.size() && model.get(target) instanceof Row.Header) {
                if (EventQueue.getCurrentEvent() instanceof MouseEvent) {
                    return;
                }
                int from = getMinSelectionIndex();
                int step = from < 0 || target > from ? 1 : -1;
                int moved = nextVersion(target, step);
                if (moved < 0) {
                    moved = nextVersion(target, -step);
                }
                if (moved < 0) {
                    return;
                }
                target = moved;
            }
            super.setSelectionInterval(target, target);
        }
    }

    /** Headings in a smaller bold grey with a fold mark, the default version in bold with its tag. */
    private final class RowRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(
                JList<?> source, Object value, int index, boolean selected, boolean focused) {
            boolean version = value instanceof Row.Version;
            JLabel label = (JLabel) super.getListCellRendererComponent(source, "", index, selected && version, false);
            Font base = source.getFont();
            if (value instanceof Row.Header header) {
                String mark = folded.contains(header.series()) ? "+ " : "- ";
                label.setText(
                        mark + text.get("versionSeries").formatted(header.series()) + " (" + header.count() + ")");
                label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 1f));
                label.setForeground(disabledColor());
                label.setBorder(BorderFactory.createEmptyBorder(4, 4, 1, 4));
            } else if (value instanceof Row.Version row) {
                label.setText(row.highlighted() ? row.id() + "  ·  " + text.get("versionDefault") : row.id());
                label.setFont(row.highlighted() ? base.deriveFont(Font.BOLD) : base);
                label.setBorder(BorderFactory.createEmptyBorder(1, 16, 1, 4));
            }
            return label;
        }
    }
}
