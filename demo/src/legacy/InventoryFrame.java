package legacy;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/** A plain Swing app in the style of the 2000s. It knows nothing about XUL-J. */
public class InventoryFrame extends JFrame {
    private final JTextField nameField = new JTextField(18);
    private final JComboBox<String> categoryCombo = new JComboBox<>(new String[] {"Hardware", "Tools", "Garden", "Paint"});
    private final JSpinner qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
    private final JCheckBox reorderCheck = new JCheckBox("Reorder when low");
    private final JButton addButton = new JButton("Add item");
    private final DefaultTableModel itemsModel = new DefaultTableModel(new Object[] {"SKU", "Name", "Category", "Qty", "Location"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable itemsTable = new JTable(itemsModel);
    private final JButton restockButton = new JButton("Restock all");
    private final JProgressBar restockProgress = new JProgressBar(0, 20);
    private final DefaultListModel<String> restockLogModel = new DefaultListModel<>();
    private final JList<String> restockLog = new JList<>(restockLogModel);
    private final JRadioButton metricRadio = new JRadioButton("Metric units", true);
    private final JRadioButton imperialRadio = new JRadioButton("Imperial units");
    private final JCheckBox confirmCheck = new JCheckBox("Confirm before deleting", true);
    private final JPasswordField apiKeyField = new JPasswordField(18);
    private final JTextArea notesArea = new JTextArea(4, 30);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel clockLabel = new JLabel();
    private final JButton deleteButton = new JButton("Delete selected");
    private int nextSku = 5001;
    private int restockLeft;
    private String location = "Aisle 1";

    public InventoryFrame() {
        super("Legacy Inventory");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setJMenuBar(buildMenus());

        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.add(deleteButton);
        tools.add(Box.createHorizontalGlue());
        tools.add(new JLabel("Location: "));
        JButton locationButton = new JButton("Change…");
        locationButton.setMnemonic(KeyEvent.VK_L);
        tools.add(locationButton);
        deleteButton.addActionListener(e -> deleteSelected());
        locationButton.addActionListener(e -> changeLocation());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Items", buildItemsTab());
        tabs.addTab("Restock", buildRestockTab());
        tabs.addTab("Settings", buildSettingsTab());

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(clockLabel, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tools, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);

        Timer clock = new Timer(1000, e -> clockLabel.setText(new SimpleDateFormat("HH:mm:ss").format(new Date())));
        clock.setInitialDelay(0);
        clock.start();
        getRootPane().setDefaultButton(addButton);
        setSize(560, 460);
    }

    private JMenuBar buildMenus() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        JMenuItem importItem = new JMenuItem("Import CSV…");
        importItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        importItem.addActionListener(e -> importCsv());
        JMenuItem exportItem = new JMenuItem("Export CSV…");
        exportItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        exportItem.addActionListener(e -> exportCsv());
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        file.add(importItem);
        file.add(exportItem);
        file.addSeparator();
        file.add(exitItem);
        JMenu view = new JMenu("View");
        view.setMnemonic(KeyEvent.VK_V);
        javax.swing.JCheckBoxMenuItem compact = new javax.swing.JCheckBoxMenuItem("Compact rows");
        compact.addActionListener(e -> { itemsTable.setRowHeight(compact.isSelected() ? 14 : 18); status(compact.isSelected() ? "Compact rows" : "Normal rows"); });
        JMenu sortMenu = new JMenu("Sort by");
        ButtonGroup sortGroup = new ButtonGroup();
        for (String col : new String[] {"SKU", "Name", "Qty"}) {
            javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(col, col.equals("SKU"));
            sortGroup.add(item);
            item.addActionListener(e -> status("Sorted by " + col));
            sortMenu.add(item);
        }
        view.add(compact);
        view.add(sortMenu);
        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this,
            "Legacy Inventory 2.3\nA Swing app served by XUL-J.", "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(aboutItem);
        bar.add(file);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JPanel buildItemsTab() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("New item"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Name:"), c);
        c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(nameField, c);
        c.gridwidth = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        c.gridx = 0; c.gridy = 1; form.add(new JLabel("Category:"), c);
        c.gridx = 1; form.add(categoryCombo, c);
        c.gridx = 2; form.add(new JLabel("Qty:"), c);
        c.gridx = 3; form.add(qtySpinner, c);
        c.gridx = 1; c.gridy = 2; form.add(reorderCheck, c);
        c.gridx = 3; c.anchor = GridBagConstraints.EAST; form.add(addButton, c);
        addButton.setMnemonic(KeyEvent.VK_A);
        addButton.addActionListener(this::addItem);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.NORTH);
        panel.add(new JScrollPane(itemsTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildRestockTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel("Simulates a slow restock from the warehouse system."));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(restockButton);
        row.add(restockProgress);
        panel.add(row);
        panel.add(new JScrollPane(restockLog));
        restockButton.setMnemonic(KeyEvent.VK_R);
        Timer step = new Timer(100, null);
        step.addActionListener(e -> {
            Object[] item = {nextSku++, "Restocked part " + restockLeft, "Hardware", 10 + restockLeft, location};
            itemsModel.addRow(item);
            restockLogModel.addElement("restocked SKU " + item[0]);
            restockProgress.setValue(restockProgress.getValue() + 1);
            if (--restockLeft == 0) {
                step.stop();
                restockButton.setEnabled(true);
                status("Restock finished: " + restockProgress.getMaximum() + " items");
            }
        });
        restockButton.addActionListener(e -> {
            restockButton.setEnabled(false);
            restockProgress.setValue(0);
            restockLogModel.clear();
            restockLeft = restockProgress.getMaximum();
            status("Restocking…");
            step.start();
        });
        return panel;
    }

    private JPanel buildSettingsTab() {
        JPanel units = new JPanel();
        units.setLayout(new BoxLayout(units, BoxLayout.Y_AXIS));
        units.setBorder(BorderFactory.createTitledBorder("Units"));
        ButtonGroup group = new ButtonGroup();
        group.add(metricRadio);
        group.add(imperialRadio);
        units.add(metricRadio);
        units.add(imperialRadio);
        imperialRadio.addActionListener(e -> status("Units: imperial"));
        metricRadio.addActionListener(e -> status("Units: metric"));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; panel.add(units, c);
        c.gridy = 1; panel.add(confirmCheck, c);
        c.gridwidth = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        c.gridy = 2; panel.add(new JLabel("API key:"), c);
        c.gridx = 1; panel.add(apiKeyField, c);
        c.gridx = 0; c.gridy = 3; panel.add(new JLabel("Notes:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH; c.weightx = 1; c.weighty = 1; panel.add(new JScrollPane(notesArea), c);
        return panel;
    }

    private void addItem(ActionEvent e) {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name is required.", "Cannot add item", JOptionPane.ERROR_MESSAGE);
            status("Item not added");
            return;
        }
        itemsModel.addRow(new Object[] {nextSku++, name, categoryCombo.getSelectedItem(), qtySpinner.getValue(), location});
        status("Added " + name + (reorderCheck.isSelected() ? " (reorder on)" : ""));
        nameField.setText("");
    }

    private void deleteSelected() {
        int row = itemsTable.getSelectedRow();
        if (row < 0) row = itemsModel.getRowCount() - 1;
        if (row < 0) { status("Nothing to delete"); return; }
        Object name = itemsModel.getValueAt(row, 1);
        if (confirmCheck.isSelected() && JOptionPane.showConfirmDialog(this, "Delete \"" + name + "\"?", "Confirm delete",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            status("Delete cancelled");
            return;
        }
        itemsModel.removeRow(row);
        status("Deleted " + name);
    }

    private void changeLocation() {
        Object answer = JOptionPane.showInputDialog(this, "Location for new items:", "Change location",
            JOptionPane.QUESTION_MESSAGE, null, null, location);
        if (answer != null && !answer.toString().trim().isEmpty()) {
            location = answer.toString().trim();
            status("Location: " + location);
        }
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export items");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setSelectedFile(new File("inventory.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { status("Export cancelled"); return; }
        try (PrintWriter w = new PrintWriter(new FileWriter(fc.getSelectedFile()))) {
            w.println("sku,name,category,qty,location");
            for (int r = 0; r < itemsModel.getRowCount(); r++) {
                StringBuilder sb = new StringBuilder();
                for (int col = 0; col < itemsModel.getColumnCount(); col++) sb.append(col > 0 ? "," : "").append(itemsModel.getValueAt(r, col));
                w.println(sb);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        status("Exported " + itemsModel.getRowCount() + " items to " + fc.getSelectedFile().getName());
    }

    private void importCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import items");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { status("Import cancelled"); return; }
        int added = 0, skipped = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(fc.getSelectedFile()))) {
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split(",");
                if (f.length < 3) { skipped++; continue; }
                try {
                    itemsModel.addRow(new Object[] {nextSku++, f[0], f[1], Integer.parseInt(f[2].trim()), f.length > 3 ? f[3] : location});
                    added++;
                } catch (NumberFormatException ex) { skipped++; }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Import failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        status("Imported " + added + " items from " + fc.getSelectedFile().getName());
        if (skipped > 0) JOptionPane.showMessageDialog(this, skipped + " lines were skipped.", "Import", JOptionPane.WARNING_MESSAGE);
    }

    private void status(String s) { statusLabel.setText(s); }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new InventoryFrame().setVisible(true));
    }
}
