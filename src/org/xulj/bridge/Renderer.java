package org.xulj.bridge;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListModel;
import javax.swing.RootPaneContainer;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumn;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

/**
 * Renders live Swing windows into a virtual XUL-J tree, diffed by Reconciler.
 * Layout comes from the layout manager where it carries structure (BorderLayout, FlowLayout,
 * BoxLayout, GridLayout, CardLayout) and from the components' real bounds otherwise
 * (GridBagLayout, GroupLayout, null, …): windows are realized on a (virtual) display, so
 * every component has been laid out by Swing itself.
 */
public final class Renderer {
    private static final int HGAP = 6; // matches .x-hbox gap in xul.css

    private final Host host;
    private final Map<Object, String> ids = new IdentityHashMap<>();
    private final Map<String, Object> byId = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private final Map<Object, String> nameHints = new IdentityHashMap<>();
    private final Map<JFileChooser, List<File>> uploads = new IdentityHashMap<>();
    private final Map<JFileChooser, String> saveNames = new IdentityHashMap<>();

    private List<VNode> nodes;
    private Map<String, Map<String, Object>> commands;
    private Map<String, Runnable> clicks;          // synthetic buttons (dialogs, file choosers)
    private Map<String, Consumer<Object>> inputs;  // synthetic inputs
    private Map<String, JFileChooser> pickers;     // filepicker id → chooser
    private Set<Object> live;
    private VNode window;                          // window being rendered
    private List<javax.swing.JPopupMenu> popups;   // context menus used in the window being rendered
    private Map<String, Object> theme;

    public Renderer(Host host) { this.host = host; }

    public List<VNode> nodes() { return nodes; }
    public Map<String, Map<String, Object>> commands() { return commands; }
    public Object lookup(String id) { return byId.get(id); }
    public Runnable click(String commandId) { return clicks.get(commandId); }
    public Consumer<Object> input(String id) { return inputs.get(id); }
    public JFileChooser picker(String id) { return pickers.get(id); }
    /** Theme tokens taken from the look and feel and the main window. */
    public Map<String, Object> theme() { return theme; }

    public void addUpload(JFileChooser fc, File f) {
        List<File> list = uploads.computeIfAbsent(fc, k -> new ArrayList<>());
        if (!fc.isMultiSelectionEnabled()) list.clear();
        list.add(f);
    }

    public void render(List<Window> windows) {
        nodes = new ArrayList<>();
        commands = new LinkedHashMap<>();
        clicks = new HashMap<>();
        inputs = new HashMap<>();
        pickers = new HashMap<>();
        live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        nameHints.clear();
        theme = windows.isEmpty() ? null : themeOf(windows.get(0));
        int order = 0;
        for (Window w : windows) {
            if (!w.isDisplayable()) continue;
            popups = new ArrayList<>();
            collectNames(w);
            window = add(w, "window", "root", order++);
            window.attrs.put("label", w instanceof Frame ? nz(((Frame) w).getTitle()) : w instanceof Dialog ? nz(((Dialog) w).getTitle()) : "");
            if (!w.isVisible()) window.attrs.put("hidden", true);
            if (w instanceof Dialog && ((Dialog) w).isModal()) {
                window.attrs.put("modal", true);
                // Escape closes a modal dialog, as the window manager would.
                String cmd = "cmd_" + window.id + "__close";
                commands.put(cmd, command(true, "escape"));
                clicks.put(cmd, () -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
            }
            int i = 0;
            if (w instanceof RootPaneContainer) {
                JRootPane rp = ((RootPaneContainer) w).getRootPane();
                if (rp.getJMenuBar() != null) menuBar(rp.getJMenuBar(), window.id, i++);
                element(rp.getContentPane(), window.id, i, flex(1));
            } else {
                layoutChildren(w, window.id, false);
            }
            // Context menus used in this window, rendered once each as menupopups.
            int k = 0;
            for (javax.swing.JPopupMenu pm : new java.util.LinkedHashSet<>(popups)) {
                VNode pop = add(pm, "menupopup", window.id, 10000 + k++);
                menuEntries(pm.getComponents(), pop.id);
            }
        }
        // Forget components that are gone so their ids can be reused.
        for (Object dead : new ArrayList<>(ids.keySet())) {
            if (live.contains(dead)) continue;
            String id = ids.remove(dead);
            byId.remove(id);
            used.remove(id);
        }
        uploads.keySet().retainAll(live);
        saveNames.keySet().retainAll(live);
    }

    // ---- ids ---------------------------------------------------------------------------

    /** Field names in the app's own classes make good ids: `private JButton addButton;`. */
    private void collectNames(Container root) {
        scanFields(root);
        for (Component c : allComponents(root)) {
            String cls = c.getClass().getName();
            if (!cls.startsWith("java.") && !cls.startsWith("javax.") && !cls.startsWith("sun.") && !cls.startsWith("com.sun.")) scanFields(c);
        }
    }

    private void scanFields(Object owner) {
        for (Class<?> k = owner.getClass(); k != null && !k.getName().startsWith("java"); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                if (!Component.class.isAssignableFrom(f.getType()) && !JFileChooser.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null && !nameHints.containsKey(v)) nameHints.put(v, f.getName());
                } catch (Exception ignored) {
                    // inaccessible (modules); fall back to generated ids
                }
            }
        }
    }

    private static List<Component> allComponents(Container c) {
        List<Component> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            out.add(k);
            if (k instanceof Container) out.addAll(allComponents((Container) k));
        }
        return out;
    }

    private String idOf(Object o) {
        String id = ids.get(o);
        if (id != null) return id;
        String name = nameHints.get(o);
        if (name == null && o instanceof Component) {
            name = ((Component) o).getName();
            // AWT/Swing invent names like "frame0" or "null.contentPane"; those are not the app's.
            if (name != null && (name.matches("^(frame|dialog|win|canvas|panel)\\d+$") || name.contains(".contentPane") || name.startsWith("null."))) name = null;
        }
        if (name == null && o instanceof Window) name = o.getClass().getSimpleName();
        if (name == null && o instanceof AbstractButton) name = slug(((AbstractButton) o).getText());
        name = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') name = o.getClass().getSimpleName().toLowerCase();
        if (name.isEmpty()) name = "c";
        name = name.replace("__", "_");
        id = name;
        for (int n = 2; used.contains(id); n++) id = name + n;
        used.add(id);
        ids.put(o, id);
        byId.put(id, o);
        return id;
    }

    /** "Import CSV…" → "importCsv": readable ids for anonymous buttons and menu items. */
    private static String slug(String label) {
        String[] words = text(label).replaceAll("[^A-Za-z0-9 ]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            String lower = w.toLowerCase();
            sb.append(sb.length() == 0 ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private VNode add(Object source, String tag, String parent, int order) {
        live.add(source);
        VNode n = new VNode(idOf(source), tag, parent, order);
        nodes.add(n);
        return n;
    }

    private VNode box(String id, String tag, String parent, int order) {
        VNode n = new VNode(id, tag, parent, order);
        nodes.add(n);
        return n;
    }

    private static Map<String, Object> flex(int f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flex", f);
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Swing labels often hold HTML; keep the text. */
    private static String text(String s) {
        if (s == null) return "";
        if (!s.regionMatches(true, 0, "<html>", 0, 6)) return s;
        return s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", "").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }

    // ---- elements ----------------------------------------------------------------------

    private void element(Component c, String parent, int order, Map<String, Object> extra) {
        VNode n = widget(c, parent, order);
        if (n == null) return;
        if (extra != null) for (Map.Entry<String, Object> e : extra.entrySet()) n.attrs.putIfAbsent(e.getKey(), e.getValue());
        if (!c.isVisible()) n.attrs.put("hidden", true);
        if (!c.isEnabled() && !n.attrs.containsKey("command")) n.attrs.put("disabled", true);
        // A scroll pane renders as its view, so the view's popup menu is the one that counts.
        Component src = c instanceof JScrollPane && ((JScrollPane) c).getViewport().getView() != null ? ((JScrollPane) c).getViewport().getView() : c;
        javax.swing.JPopupMenu pm = src instanceof JComponent ? ((JComponent) src).getComponentPopupMenu() : null;
        if (pm != null && popups != null) {
            popups.add(pm);
            n.attrs.put("contextmenu", idOf(pm));
        }
    }

    // ---- theme and semantic colours ----------------------------------------------------------

    private static String hex(java.awt.Color c) { return c == null ? null : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    private static java.awt.Color ui(String... keys) {
        for (String k : keys) {
            java.awt.Color c = javax.swing.UIManager.getColor(k);
            if (c != null) return c;
        }
        return null;
    }

    private static Map<String, Object> themeOf(Window w) {
        Map<String, Object> t = new LinkedHashMap<>();
        java.awt.Color bg = w instanceof RootPaneContainer ? ((RootPaneContainer) w).getContentPane().getBackground() : w.getBackground();
        putColor(t, "background", bg != null ? bg : ui("Panel.background"));
        putColor(t, "chrome", ui("MenuBar.background", "Panel.background"));
        putColor(t, "surface", ui("Table.background", "TextField.background", "List.background"));
        putColor(t, "text", ui("Label.foreground", "textText"));
        putColor(t, "muted", ui("Label.disabledForeground", "textInactiveText"));
        putColor(t, "border", ui("Separator.foreground", "controlShadow"));
        putColor(t, "accent", ui("Table.selectionBackground", "List.selectionBackground", "textHighlight"));
        putColor(t, "accentText", ui("Table.selectionForeground", "List.selectionForeground", "textHighlightText"));
        String laf = javax.swing.UIManager.getLookAndFeel().getName().toLowerCase();
        t.put("radius", laf.contains("nimbus") || laf.contains("flat") ? 6 : 2);
        java.awt.Font f = javax.swing.UIManager.getFont("Label.font");
        if (f != null) {
            String family = f.getFamily().toLowerCase();
            t.put("density", f.getSize() <= 11 ? "compact" : f.getSize() >= 14 ? "comfortable" : "normal");
            t.put("font", family.contains("mono") ? "mono" : family.equals("serif") || family.contains("times") ? "serif" : "system");
        }
        return t;
    }

    private static void putColor(Map<String, Object> t, String k, java.awt.Color c) { if (c != null) t.put(k, hex(c)); }

    /** A label painted in a colour of its own gets a role, so it still reads in dark mode. */
    private static String roleOf(java.awt.Color fore, java.awt.Color parentFore) {
        if (fore == null || fore.equals(parentFore) || fore.equals(javax.swing.UIManager.getColor("Label.foreground"))) return null;
        float[] hsb = java.awt.Color.RGBtoHSB(fore.getRed(), fore.getGreen(), fore.getBlue(), null);
        float hue = hsb[0] * 360, sat = hsb[1], bright = hsb[2];
        if (sat < 0.15f) return bright > 0.3f && bright < 0.8f ? "muted" : null;
        if (hue < 18 || hue >= 340) return "danger";
        if (hue < 65) return "warning";
        if (hue < 170) return "success";
        return null;
    }

    private VNode widget(Component c, String parent, int order) {
        VNode n;
        if (c instanceof JOptionPane) return optionPane((JOptionPane) c, parent, order);
        if (c instanceof JFileChooser) return fileChooser((JFileChooser) c, parent, order);
        if (c instanceof JMenuBar) return menuBar((JMenuBar) c, parent, order);
        if (c instanceof JSeparator || c instanceof JToolBar.Separator) return null;
        if (c instanceof Box.Filler) {
            if (((Box.Filler) c).getMaximumSize().width < Short.MAX_VALUE && ((Box.Filler) c).getMaximumSize().height < Short.MAX_VALUE) return null;
            n = add(c, "spacer", parent, order); // glue
            n.attrs.put("flex", 1);
            return n;
        }
        if (c instanceof JToolBar) {
            n = add(c, "toolbar", parent, order);
            int i = 0;
            for (Component k : ((JToolBar) c).getComponents()) element(k, n.id, i++, null);
            return n;
        }
        if (c instanceof JScrollPane) {
            Component view = ((JScrollPane) c).getViewport().getView();
            if (view == null) return null;
            live.add(c);
            return widget(view, parent, order);
        }
        if (c instanceof JSplitPane) {
            JSplitPane sp = (JSplitPane) c;
            boolean horizontal = sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
            n = add(c, horizontal ? "hbox" : "vbox", parent, order);
            n.attrs.put("align", "stretch");
            Map<String, Object> first = new LinkedHashMap<>();
            first.put(horizontal ? "width" : "height", Math.max(0, sp.getDividerLocation()));
            if (sp.getLeftComponent() != null) element(sp.getLeftComponent(), n.id, 0, first);
            if (sp.getRightComponent() != null) element(sp.getRightComponent(), n.id, 1, flex(1));
            return n;
        }
        if (c instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) c;
            n = add(c, "tabbox", parent, order);
            for (int i = 0; i < tp.getTabCount(); i++) {
                Component page = tp.getComponentAt(i);
                if (page == null) continue;
                VNode panel = box(n.id + "__tab" + i, "tabpanel", n.id, i);
                panel.attrs.put("label", text(tp.getTitleAt(i)));
                panel.attrs.put("flex", 1);
                Map<String, Object> extra = flex(1);
                element(page, panel.id, 0, extra);
                // CardLayout-style hiding of inactive tabs is the client's job.
                for (VNode v : nodes) if (v.parent.equals(panel.id)) v.attrs.remove("hidden");
            }
            return n;
        }
        if (c instanceof JTable) return table((JTable) c, parent, order);
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            n = add(c, "tree", parent, order);
            n.attrs.put("cols", Arrays.asList(col("c0", "Items", null)));
            n.rows = new ArrayList<>();
            ListModel<?> m = list.getModel();
            for (int i = 0; i < m.getSize(); i++) n.rows.add(row("c0", String.valueOf(m.getElementAt(i))));
            n.attrs.put("seltype", list.getSelectionMode() == javax.swing.ListSelectionModel.SINGLE_SELECTION ? "single" : "multiple");
            n.attrs.put("selection", ints(list.getSelectedIndices()));
            return n;
        }
        if (c instanceof JTree) {
            JTree tree = (JTree) c;
            n = add(c, "tree", parent, order);
            n.attrs.put("cols", Arrays.asList(col("c0", "Items", null)));
            n.rows = new ArrayList<>();
            for (int i = 0; i < tree.getRowCount(); i++) {
                TreePath p = tree.getPathForRow(i);
                int depth = p.getPathCount() - (tree.isRootVisible() ? 1 : 2);
                StringBuilder indent = new StringBuilder();
                for (int d = 0; d < depth; d++) indent.append("   ");
                n.rows.add(row("c0", indent + String.valueOf(p.getLastPathComponent())));
            }
            n.attrs.put("seltype", tree.getSelectionModel().getSelectionMode() == javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION ? "single" : "multiple");
            int[] sel = tree.getSelectionRows();
            n.attrs.put("selection", ints(sel == null ? new int[0] : sel));
            return n;
        }
        if (c instanceof JTextArea || c instanceof JEditorPane) {
            JTextComponent t = (JTextComponent) c;
            n = add(c, "textbox", parent, order);
            n.attrs.put("multiline", true);
            n.attrs.put("value", t.getText());
            if (!t.isEditable()) n.attrs.put("disabled", true);
            return n;
        }
        if (c instanceof JTextComponent) {
            JTextComponent t = (JTextComponent) c;
            n = add(c, "textbox", parent, order);
            n.attrs.put("value", t.getText());
            if (c instanceof JPasswordField) n.attrs.put("password", true);
            if (!t.isEditable()) n.attrs.put("disabled", true);
            return n;
        }
        if (c instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) c;
            n = add(c, "menulist", parent, order);
            List<Object> opts = new ArrayList<>();
            for (int i = 0; i < combo.getItemCount(); i++) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", String.valueOf(i));
                o.put("label", String.valueOf(combo.getItemAt(i)));
                opts.add(o);
            }
            n.attrs.put("options", opts);
            if (combo.getSelectedIndex() >= 0) n.attrs.put("selectedIndex", combo.getSelectedIndex());
            return n;
        }
        if (c instanceof JSpinner) {
            n = add(c, "textbox", parent, order);
            n.attrs.put("value", String.valueOf(((JSpinner) c).getValue()));
            return n;
        }
        if (c instanceof JToggleButton) { // JCheckBox, JRadioButton
            n = add(c, "checkbox", parent, order);
            n.attrs.put("label", text(((AbstractButton) c).getText()));
            n.attrs.put("value", ((AbstractButton) c).isSelected());
            return n;
        }
        if (c instanceof AbstractButton) return button((AbstractButton) c, parent, order, "button", "");
        if (c instanceof JLabel) {
            n = add(c, "label", parent, order);
            n.attrs.put("value", text(((JLabel) c).getText()));
            String role = roleOf(c.getForeground(), c.getParent() != null ? c.getParent().getForeground() : null);
            if (role != null) n.attrs.put("class", role);
            return n;
        }
        if (c instanceof JProgressBar) {
            n = add(c, "progressmeter", parent, order);
            n.attrs.put("value", ((JProgressBar) c).getPercentComplete());
            return n;
        }
        if (c instanceof JSlider) {
            JSlider s = (JSlider) c;
            n = add(c, "progressmeter", parent, order);
            n.attrs.put("value", s.getMaximum() > s.getMinimum() ? (double) (s.getValue() - s.getMinimum()) / (s.getMaximum() - s.getMinimum()) : 0.0);
            return n;
        }
        if (c instanceof Container && (((Container) c).getComponentCount() > 0 || c.getClass() == JPanel.class)) {
            Container ct = (Container) c;
            String title = titleOf(c);
            if (title != null) {
                n = add(c, "groupbox", parent, order);
                n.attrs.put("label", title);
                layoutChildren(ct, n.id, false);
                return n;
            }
            boolean horizontal = isHorizontal(ct.getLayout());
            n = add(c, horizontal ? "hbox" : "vbox", parent, order);
            layoutChildren(ct, n.id, horizontal);
            return n;
        }
        n = add(c, "description", parent, order);
        n.attrs.put("value", "[" + c.getClass().getSimpleName() + "]");
        n.attrs.put("class", "muted");
        return n;
    }

    private static String titleOf(Component c) {
        if (!(c instanceof JComponent)) return null;
        Border b = ((JComponent) c).getBorder();
        while (b instanceof CompoundBorder) {
            Border outer = ((CompoundBorder) b).getOutsideBorder();
            b = outer instanceof TitledBorder ? outer : ((CompoundBorder) b).getInsideBorder();
        }
        return b instanceof TitledBorder ? ((TitledBorder) b).getTitle() : null;
    }

    private static boolean isHorizontal(LayoutManager lm) {
        if (lm instanceof FlowLayout) return true;
        if (lm instanceof BoxLayout) {
            int axis = ((BoxLayout) lm).getAxis();
            return axis == BoxLayout.X_AXIS || axis == BoxLayout.LINE_AXIS;
        }
        return false;
    }

    // ---- buttons, commands, keys -------------------------------------------------------

    private VNode button(AbstractButton b, String parent, int order, String tag, String path) {
        VNode n = add(b, tag, parent, order);
        String label = text(b.getText());
        if (label.isEmpty()) label = b.getToolTipText() != null ? b.getToolTipText() : b.getActionCommand() != null ? b.getActionCommand() : "…";
        n.attrs.put("label", path + label);
        JRootPane rp = b.getRootPane();
        if (rp != null && rp.getDefaultButton() == b) n.attrs.put("class", "primary");
        String key = null;
        if (b instanceof JMenuItem && !(b instanceof JMenu)) key = keyName(((JMenuItem) b).getAccelerator());
        // A menu item's mnemonic only works inside its open menu, so it is not a global shortcut.
        if (key == null && !(b instanceof JMenuItem) && b.getMnemonic() != 0 && Character.isLetterOrDigit(b.getMnemonic()))
            key = "alt+" + Character.toLowerCase((char) b.getMnemonic());
        String cmd = "cmd_" + n.id;
        n.attrs.put("command", cmd);
        commands.put(cmd, command(b.isEnabled(), key));
        return n;
    }

    private static Map<String, Object> command(boolean enabled, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disabled", !enabled);
        if (key != null && key.matches("^((ctrl|alt|shift|meta)\\+)*[a-z0-9]+$")) m.put("key", key);
        return m;
    }

    private static String keyName(KeyStroke ks) {
        if (ks == null) return null;
        StringBuilder sb = new StringBuilder();
        int m = ks.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append("alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("shift+");
        if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append("meta+");
        int code = ks.getKeyCode();
        String name;
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z || code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) name = String.valueOf((char) Character.toLowerCase(code));
        else if (code == KeyEvent.VK_ENTER) name = "enter";
        else if (code == KeyEvent.VK_ESCAPE) name = "escape";
        else if (code == KeyEvent.VK_DELETE) name = "delete";
        else if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) name = "f" + (code - KeyEvent.VK_F1 + 1);
        else return null;
        return sb.append(name).toString();
    }

    /** Menus open and close in the browser; only choosing a leaf item reaches the bridge. */
    private VNode menuBar(JMenuBar bar, String parent, int order) {
        VNode n = add(bar, "menubar", parent, order);
        int i = 0;
        for (int m = 0; m < bar.getMenuCount(); m++) {
            JMenu menu = bar.getMenu(m);
            if (menu != null) menu(menu, n.id, i++, true);
        }
        return n;
    }

    private VNode menu(JMenu menu, String parent, int order, boolean topLevel) {
        VNode n = add(menu, "menu", parent, order);
        n.attrs.put("label", text(menu.getText()));
        if (topLevel && menu.getMnemonic() != 0 && Character.isLetterOrDigit(menu.getMnemonic()))
            n.attrs.put("accesskey", "alt+" + Character.toLowerCase((char) menu.getMnemonic()));
        if (!menu.isEnabled()) n.attrs.put("disabled", true);
        if (!menu.isVisible()) n.attrs.put("hidden", true);
        menuEntries(menu.getMenuComponents(), n.id);
        return n;
    }

    private void menuEntries(Component[] items, String parent) {
        int i = 0;
        for (Component k : items) {
            VNode child;
            if (k instanceof JMenu) child = menu((JMenu) k, parent, i, false);
            else if (k instanceof JMenuItem) {
                child = button((JMenuItem) k, parent, i, "menuitem", "");
                if (k instanceof javax.swing.JCheckBoxMenuItem || k instanceof javax.swing.JRadioButtonMenuItem)
                    child.attrs.put("checked", ((JMenuItem) k).isSelected());
            } else if (k instanceof JSeparator) child = add(k, "menuseparator", parent, i);
            else continue;
            if (!k.isVisible()) child.attrs.put("hidden", true);
            i++;
        }
    }

    // ---- data widgets --------------------------------------------------------------------

    private VNode table(JTable t, String parent, int order) {
        VNode n = add(t, "tree", parent, order);
        List<Object> cols = new ArrayList<>();
        int count = t.getColumnCount();
        for (int i = 0; i < count; i++) {
            TableColumn tc = t.getColumnModel().getColumn(i);
            boolean last = i == count - 1;
            cols.add(col("c" + i, t.getColumnName(i), last ? null : tc.getWidth()));
        }
        n.attrs.put("cols", cols);
        n.attrs.put("class", "mono");
        n.rows = new ArrayList<>();
        for (int r = 0; r < t.getRowCount(); r++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                Object v = t.getValueAt(r, i);
                row.put("c" + i, v == null ? "" : String.valueOf(v));
            }
            n.rows.add(row);
        }
        n.attrs.put("seltype", t.getSelectionModel().getSelectionMode() == javax.swing.ListSelectionModel.SINGLE_SELECTION ? "single" : "multiple");
        n.attrs.put("selection", ints(t.getSelectedRows()));
        return n;
    }

    private static List<Integer> ints(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int i : a) out.add(i);
        java.util.Collections.sort(out);
        return out;
    }

    private static Map<String, Object> col(String id, String label, Integer width) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label == null ? "" : label);
        if (width != null) m.put("width", Math.max(20, width));
        else m.put("flex", 1);
        return m;
    }

    private static Map<String, Object> row(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // ---- dialogs -----------------------------------------------------------------------------

    /** JOptionPane: message, optional input, and its buttons in a right-aligned row. */
    private VNode optionPane(JOptionPane p, String parent, int order) {
        switch (p.getMessageType()) {
            case JOptionPane.ERROR_MESSAGE: window.attrs.put("icon", "error"); break;
            case JOptionPane.WARNING_MESSAGE: window.attrs.put("icon", "warning"); break;
            case JOptionPane.QUESTION_MESSAGE: window.attrs.put("icon", "question"); break;
            case JOptionPane.INFORMATION_MESSAGE: window.attrs.put("icon", "info"); break;
            default: break;
        }
        VNode n = add(p, "vbox", parent, order);
        int i = 0;
        Object msg = p.getMessage();
        List<Object> parts = msg instanceof Object[] ? Arrays.asList((Object[]) msg) : Arrays.asList(msg);
        StringBuilder sb = new StringBuilder();
        List<Component> custom = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Component) custom.add((Component) part);
            else if (part != null) sb.append(sb.length() > 0 ? "\n" : "").append(text(String.valueOf(part)));
        }
        VNode text = box(n.id + "__msg", "description", n.id, i++);
        text.attrs.put("value", sb.toString());
        for (Component k : custom) element(k, n.id, i++, null);
        List<Component> all = allComponents(p);
        if (p.getWantsInput()) {
            for (Component k : all) {
                if ((k instanceof JTextComponent || k instanceof JComboBox) && !custom.contains(k) && !isInside(k, custom)) {
                    element(k, n.id, i++, flex(1));
                    break;
                }
            }
        }
        VNode row = box(n.id + "__buttons", "hbox", n.id, i);
        VNode spacer = box(row.id + "__s", "spacer", row.id, 0);
        spacer.attrs.put("flex", 1);
        int b = 1;
        for (Component k : all) {
            if (k instanceof JButton && !isInside(k, custom)) element(k, row.id, b++, null);
        }
        return n;
    }

    private static boolean isInside(Component k, List<Component> roots) {
        for (Component r : roots) {
            for (Component p = k; p != null; p = p.getParent()) if (p == r) return true;
        }
        return false;
    }

    /** JFileChooser: open → browser upload; save → file name, then browser download. */
    private VNode fileChooser(JFileChooser fc, String parent, int order) {
        VNode n = add(fc, "vbox", parent, order);
        boolean save = fc.getDialogType() == JFileChooser.SAVE_DIALOG;
        VNode msg = box(n.id + "__msg", "description", n.id, 0);
        msg.attrs.put("value", save ? "The file will be downloaded by your browser."
            : "Choose " + (fc.isMultiSelectionEnabled() ? "files" : "a file") + " from your computer to upload to the application.");
        VNode row = box(n.id + "__row", "hbox", n.id, 1);
        String okCmd = "cmd_" + n.id + "__ok";
        String cancelCmd = "cmd_" + n.id + "__cancel";
        boolean ready;
        if (save) {
            VNode label = box(n.id + "__namelabel", "label", row.id, 0);
            label.attrs.put("value", "File name:");
            String nameId = n.id + "__name";
            String current = saveNames.get(fc);
            if (current == null) current = fc.getSelectedFile() != null ? fc.getSelectedFile().getName() : "untitled";
            saveNames.put(fc, current);
            VNode box = box(nameId, "textbox", row.id, 1);
            box.attrs.put("value", current);
            box.attrs.put("flex", 1);
            inputs.put(nameId, v -> saveNames.put(fc, String.valueOf(v)));
            ready = !Host.safeName(current).isEmpty();
            clicks.put(okCmd, () -> {
                String name = Host.safeName(saveNames.get(fc));
                FileFilter f = fc.getFileFilter();
                if (!name.contains(".") && f instanceof FileNameExtensionFilter) name += "." + ((FileNameExtensionFilter) f).getExtensions()[0];
                fc.setSelectedFile(host.expectDownload(name));
                fc.approveSelection();
            });
        } else {
            String pickerId = n.id + "__picker";
            VNode picker = box(pickerId, "filepicker", row.id, 0);
            picker.attrs.put("flex", 1);
            String accept = acceptOf(fc.getFileFilter());
            if (accept != null) picker.attrs.put("accept", accept);
            if (fc.isMultiSelectionEnabled()) picker.attrs.put("multiple", true);
            List<File> files = uploads.getOrDefault(fc, new ArrayList<>());
            if (!files.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (File f : files) sb.append(sb.length() > 0 ? ", " : "").append(f.getName());
                picker.attrs.put("value", sb.toString());
            }
            pickers.put(pickerId, fc);
            ready = !files.isEmpty();
            clicks.put(okCmd, () -> {
                List<File> chosen = uploads.getOrDefault(fc, new ArrayList<>());
                if (chosen.isEmpty()) return;
                if (fc.isMultiSelectionEnabled()) fc.setSelectedFiles(chosen.toArray(new File[0]));
                fc.setSelectedFile(chosen.get(0));
                fc.approveSelection();
            });
        }
        clicks.put(cancelCmd, fc::cancelSelection);
        VNode buttons = box(n.id + "__buttons", "hbox", n.id, 2);
        box(buttons.id + "__s", "spacer", buttons.id, 0).attrs.put("flex", 1);
        VNode ok = box(n.id + "__ok", "button", buttons.id, 1);
        ok.attrs.put("label", save ? "Save" : "Open");
        ok.attrs.put("class", "primary");
        ok.attrs.put("command", okCmd);
        commands.put(okCmd, command(ready, null));
        VNode cancel = box(n.id + "__cancel", "button", buttons.id, 2);
        cancel.attrs.put("label", "Cancel");
        cancel.attrs.put("command", cancelCmd);
        commands.put(cancelCmd, command(true, "escape"));
        return n;
    }

    private static String acceptOf(FileFilter f) {
        if (!(f instanceof FileNameExtensionFilter)) return null;
        StringBuilder sb = new StringBuilder();
        for (String e : ((FileNameExtensionFilter) f).getExtensions()) sb.append(sb.length() > 0 ? "," : "").append('.').append(e);
        return sb.toString();
    }

    // ---- layout --------------------------------------------------------------------------------

    private void layoutChildren(Container ct, String parentId, boolean horizontal) {
        Component[] kids = ct.getComponents();
        if (kids.length == 0) return;
        LayoutManager lm = ct.getLayout();

        if (lm instanceof BorderLayout) {
            BorderLayout bl = (BorderLayout) lm;
            Component north = bl.getLayoutComponent(ct, BorderLayout.NORTH);
            Component south = bl.getLayoutComponent(ct, BorderLayout.SOUTH);
            Component west = bl.getLayoutComponent(ct, BorderLayout.WEST);
            Component east = bl.getLayoutComponent(ct, BorderLayout.EAST);
            Component center = bl.getLayoutComponent(ct, BorderLayout.CENTER);
            int i = 0;
            if (north != null) element(north, parentId, i++, null);
            if (west != null || east != null) {
                VNode mid = box(parentId + "__m", "hbox", parentId, i++);
                mid.attrs.put("flex", 1);
                mid.attrs.put("align", "stretch");
                int j = 0;
                if (west != null) element(west, mid.id, j++, widthOf(west));
                if (center != null) element(center, mid.id, j++, flex(1));
                if (east != null) element(east, mid.id, j, widthOf(east));
            } else if (center != null) element(center, parentId, i++, flex(1));
            if (south != null) element(south, parentId, i, null);
            return;
        }
        if (lm instanceof FlowLayout) {
            int align = ((FlowLayout) lm).getAlignment();
            int i = 0;
            boolean right = align == FlowLayout.RIGHT || align == FlowLayout.TRAILING;
            boolean center = align == FlowLayout.CENTER;
            if (right || center) box(parentId + "__s0", "spacer", parentId, i++).attrs.put("flex", 1);
            for (Component k : kids) element(k, parentId, i++, null);
            if (center) box(parentId + "__s1", "spacer", parentId, i).attrs.put("flex", 1);
            return;
        }
        if (lm instanceof BoxLayout) {
            int i = 0;
            for (Component k : kids) element(k, parentId, i++, stretchy(k) ? flex(1) : null);
            return;
        }
        if (lm instanceof GridLayout) {
            GridLayout gl = (GridLayout) lm;
            int cols = gl.getColumns() > 0 ? gl.getColumns() : (int) Math.ceil(kids.length / (double) Math.max(1, gl.getRows()));
            for (int r = 0; r * cols < kids.length; r++) {
                VNode row = box(parentId + "__g" + r, "hbox", parentId, r);
                for (int k = 0; k < cols && r * cols + k < kids.length; k++) element(kids[r * cols + k], row.id, k, flex(1));
            }
            return;
        }
        if (lm instanceof CardLayout) {
            int i = 0;
            for (Component k : kids) element(k, parentId, i++, flex(1));
            return;
        }
        freeLayout(ct, kids, parentId, lm instanceof GridBagLayout ? (GridBagLayout) lm : null);
    }

    private static boolean hugsRight(Component c, int innerLeft, int innerRight, GridBagLayout gbl) {
        if (gbl != null) {
            GridBagConstraints g = gbl.getConstraints(c);
            if (g.fill == GridBagConstraints.HORIZONTAL || g.fill == GridBagConstraints.BOTH) return false;
            int a = g.anchor;
            if (a == GridBagConstraints.EAST || a == GridBagConstraints.NORTHEAST || a == GridBagConstraints.SOUTHEAST
                || a == GridBagConstraints.LINE_END || a == GridBagConstraints.FIRST_LINE_END || a == GridBagConstraints.LAST_LINE_END) return true;
        }
        Rectangle b = c.getBounds();
        return b.x + b.width >= innerRight - 24 && b.x > (innerLeft + innerRight) / 2;
    }

    private static boolean stretchy(Component c) {
        return c instanceof JScrollPane || c instanceof JSplitPane || c instanceof JTabbedPane || c instanceof JTextArea
            || c instanceof JTable || c instanceof JList || c instanceof JTree;
    }

    private static Map<String, Object> widthOf(Component c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!(c instanceof JLabel)) m.put("width", Math.max(0, c.getWidth())); // labels size to their text
        return m;
    }

    /**
     * Bounds-based layout for everything else: group components into rows by vertical
     * overlap, order by x, size each cell up to the next one's start (reproducing column
     * alignment), stretch what spans the container, right-align what hugs its right edge.
     */
    private void freeLayout(Container ct, Component[] kids, String parentId, GridBagLayout gbl) {
        Insets in = ct.getInsets();
        int innerLeft = in.left, innerRight = ct.getWidth() - in.right, innerBottom = ct.getHeight() - in.bottom;
        List<Component> sorted = new ArrayList<>(Arrays.asList(kids));
        sorted.removeIf(k -> !k.isVisible() && k.getWidth() == 0);
        sorted.sort(Comparator.comparingInt(Component::getY).thenComparingInt(Component::getX));
        List<List<Component>> rows = new ArrayList<>();
        int bottom = Integer.MIN_VALUE;
        for (Component k : sorted) {
            if (rows.isEmpty() || k.getY() >= bottom - 4) { rows.add(new ArrayList<>()); bottom = Integer.MIN_VALUE; }
            rows.get(rows.size() - 1).add(k);
            bottom = Math.max(bottom, k.getY() + Math.min(k.getHeight(), 40));
        }
        int r = 0;
        for (List<Component> row : rows) {
            row.sort(Comparator.comparingInt(Component::getX));
            VNode hb = box(parentId + "__r" + r, "hbox", parentId, r);
            r++;
            boolean stretchV = false;
            boolean spaced = false;
            int i = 0;
            for (int k = 0; k < row.size(); k++) {
                Component c = row.get(k);
                Rectangle b = c.getBounds();
                boolean nextHugsRight = k + 1 < row.size() && hugsRight(row.get(k + 1), innerLeft, innerRight, gbl);
                boolean stretchH, tall;
                if (gbl != null) {
                    GridBagConstraints g = gbl.getConstraints(c);
                    stretchH = g.weightx > 0 && (g.fill == GridBagConstraints.HORIZONTAL || g.fill == GridBagConstraints.BOTH);
                    tall = g.weighty > 0 && (g.fill == GridBagConstraints.VERTICAL || g.fill == GridBagConstraints.BOTH);
                } else {
                    int span = innerRight - innerLeft;
                    stretchH = span > 200 && b.width >= span * 0.6 || (b.x <= innerLeft + 16 && b.x + b.width >= innerRight - 16 && span > 0);
                    tall = stretchy(c) && b.y + b.height >= innerBottom - 16;
                }
                if (!stretchH && hugsRight(c, innerLeft, innerRight, gbl) && !spaced) {
                    box(hb.id + "__s", "spacer", hb.id, i++).attrs.put("flex", 1);
                    spaced = true;
                }
                Map<String, Object> extra = new LinkedHashMap<>();
                if (stretchH) extra.put("flex", 1);
                else if (k + 1 < row.size() && !spaced && !nextHugsRight && row.get(k + 1).getX() > b.x) extra.put("width", Math.max(0, row.get(k + 1).getX() - b.x - HGAP));
                else if (!(c instanceof JLabel)) extra.put("width", Math.max(0, b.width));
                if (tall) stretchV = true;
                else if (stretchy(c)) extra.put("height", Math.max(0, b.height));
                element(c, hb.id, i++, extra);
            }
            hb.attrs.put("align", stretchV ? "stretch" : "center");
            if (stretchV) hb.attrs.put("flex", 1);
        }
    }
}
