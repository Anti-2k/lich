package dev.lich.installer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The installer's window: pick Prism's folder, a new or an existing instance, and Java; then install or uninstall. */
final class InstallerWindow {
    private final JFrame frame = new JFrame("Lich " + PrismInstaller.version() + " installer for Prism Launcher");
    private final JTextField prismField = new JTextField(36);
    private final JRadioButton newRadio = new JRadioButton("New instance", true);
    private final JRadioButton existingRadio = new JRadioButton("Existing instance");
    private final JTextField nameField = new JTextField(24);
    private final JComboBox<String> mcCombo = new JComboBox<>(new String[]{"1.8.9", "1.7.10"});
    private final JCheckBox forgeCheck = new JCheckBox();
    private final JComboBox<Entry> instanceCombo = new JComboBox<>();
    private final JTextField javaField = new JTextField(36);
    private final JButton installButton = new JButton("Install");
    private final JButton uninstallButton = new JButton("Uninstall");
    private final JTextArea log = new JTextArea(9, 60);
    /** Whether the name is still the one the installer suggested, so changing the version may change it. */
    private boolean defaultName = true;

    /** An instance in the list: its folder and the line describing it. */
    private record Entry(Path dir, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private InstallerWindow() {
    }

    static void open() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // The default look and feel works too.
            }
            new InstallerWindow().show();
        });
    }

    private void show() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        row(form, c, 0, new JLabel("Prism folder"), prismField, browse(prismField, true));

        ButtonGroup mode = new ButtonGroup();
        mode.add(newRadio);
        mode.add(existingRadio);
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        form.add(newRadio, c);
        JPanel newPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        newPanel.add(new JLabel("Name"));
        newPanel.add(nameField);
        newPanel.add(new JLabel("Minecraft"));
        newPanel.add(mcCombo);
        c.gridy = 2;
        c.insets = new Insets(0, 24, 6, 3);
        form.add(newPanel, c);
        c.insets = new Insets(3, 3, 3, 3);
        c.gridy = 3;
        form.add(existingRadio, c);
        c.gridy = 4;
        c.insets = new Insets(0, 24, 6, 3);
        form.add(instanceCombo, c);
        c.insets = new Insets(3, 3, 3, 3);
        c.gridy = 5;
        form.add(forgeCheck, c);
        c.gridwidth = 1;

        row(form, c, 6, new JLabel("Java 25+"), javaField, browse(javaField, false));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        buttons.add(uninstallButton);
        buttons.add(installButton);
        buttons.add(close);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(new JScrollPane(log), BorderLayout.CENTER);

        frame.getContentPane().add(form, BorderLayout.NORTH);
        frame.getContentPane().add(bottom, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        mcCombo.addActionListener(e -> updateNewInstance());
        forgeCheck.setSelected(true);
        forgeCheck.addActionListener(e -> updateNewInstance());
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }

            private void edited() {
                if (!nameField.getText().equals(PrismInstaller.defaultName((String) mcCombo.getSelectedItem(), forgeCheck.isSelected()))) {
                    defaultName = false;
                }
            }
        });
        newRadio.addActionListener(e -> updateButtons());
        existingRadio.addActionListener(e -> updateButtons());
        instanceCombo.addActionListener(e -> updateButtons());
        prismField.addActionListener(e -> loadInstances());
        installButton.addActionListener(e -> run(false));
        uninstallButton.addActionListener(e -> run(true));
        close.addActionListener(e -> frame.dispose());

        List<Path> found = PrismLauncher.findDataDirs();
        if (!found.isEmpty()) {
            prismField.setText(found.getFirst().toString());
        } else {
            log("Prism Launcher's data folder wasn't found. Choose it above: the folder with prismlauncher.cfg.");
        }
        try {
            javaField.setText(PrismLauncher.currentJava().toString());
        } catch (IllegalStateException e) {
            log("Choose the Java 25 the game should run on.");
        }
        updateNewInstance();
        loadInstances();

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void updateNewInstance() {
        String mc = (String) mcCombo.getSelectedItem();
        updateButtons();
        if (defaultName) {
            nameField.setText(PrismInstaller.defaultName(mc, forgeCheck.isSelected()));
            defaultName = true;
        }
    }

    private void loadInstances() {
        instanceCombo.removeAllItems();
        Path dataDir = Path.of(prismField.getText().strip());
        if (PrismLauncher.isDataDir(dataDir)) {
            try {
                for (Path dir : PrismLauncher.instances(dataDir)) {
                    instanceCombo.addItem(new Entry(dir, PrismInstaller.describe(dir)));
                }
            } catch (IOException e) {
                log("Can't list the instances: " + e.getMessage());
            }
        }
        existingRadio.setEnabled(instanceCombo.getItemCount() > 0);
        if (instanceCombo.getItemCount() == 0) {
            newRadio.setSelected(true);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean existing = existingRadio.isSelected();
        Entry entry = (Entry) instanceCombo.getSelectedItem();
        instanceCombo.setEnabled(existing);
        nameField.setEnabled(!existing);
        mcCombo.setEnabled(!existing);
        // An instance that has Forge always gets Lich's build; the box only decides whether one without gets it.
        forgeCheck.setText(existing ? "Add Forge if missing" : "Forge " + PrismInstance.FORGE_VERSIONS.get((String) mcCombo.getSelectedItem()));
        uninstallButton.setEnabled(existing && entry != null && PrismComponent.isInstalled(entry.dir()));
    }

    /** Installs or uninstalls off the event thread, so the window stays responsive while jars are copied. */
    private void run(boolean uninstall) {
        Path dataDir = Path.of(prismField.getText().strip());
        if (!PrismLauncher.isDataDir(dataDir)) {
            error("That isn't Prism's data folder: it has no prismlauncher.cfg.");
            return;
        }
        Entry entry = existingRadio.isSelected() ? (Entry) instanceCombo.getSelectedItem() : null;
        if (existingRadio.isSelected() && entry == null) {
            error("Choose an instance.");
            return;
        }
        String name = nameField.getText().strip();
        if (entry == null && name.isEmpty()) {
            error("Give the new instance a name.");
            return;
        }
        if (!prismClosed()) {
            return;
        }
        String mc = (String) mcCombo.getSelectedItem();
        boolean forge = forgeCheck.isSelected();
        Path java = Path.of(javaField.getText().strip());
        PrismInstaller.Ui ui = new PrismInstaller.Ui() {
            @Override
            public void log(String line) {
                SwingUtilities.invokeLater(() -> InstallerWindow.this.log(line));
            }

            @Override
            public boolean confirm(String question) {
                AtomicBoolean yes = new AtomicBoolean();
                try {
                    SwingUtilities.invokeAndWait(() -> yes.set(JOptionPane.showConfirmDialog(frame, question,
                            "Lich installer", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException(e.getCause());
                }
                return yes.get();
            }
        };

        installButton.setEnabled(false);
        uninstallButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                if (uninstall) {
                    PrismInstaller.uninstall(entry.dir(), ui);
                } else {
                    PrismInstaller.install(dataDir, entry == null ? null : entry.dir(), name, mc, forge, java, ui);
                }
                return null;
            }

            @Override
            protected void done() {
                installButton.setEnabled(true);
                try {
                    get();
                    if (!uninstall) {
                        JOptionPane.showMessageDialog(frame, "Lich is installed. Start Prism and launch the instance"
                                + (entry == null ? " (it's in Prism's list; if Prism was open, restart it)." : "."),
                                "Lich installer", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof PrismInstaller.InstallerException ? cause.getMessage() : "Failed: " + cause;
                    InstallerWindow.this.log(message);
                    error(message);
                }
                loadInstances();
            }
        }.execute();
    }

    /** Asks the person to close Prism while it runs; false if they cancel. */
    private boolean prismClosed() {
        while (PrismLauncher.isRunning()) {
            Object[] options = {"Retry", "Install anyway", "Cancel"};
            int choice = JOptionPane.showOptionDialog(frame, "Prism Launcher is running. Close it first: it keeps instance"
                            + " settings in memory and would undo the installer's changes.", "Lich installer",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice == 1) {
                return true;
            }
            if (choice != 0) {
                return false;
            }
        }
        return true;
    }

    private JButton browse(JTextField field, boolean directory) {
        JButton button = new JButton("Browse…");
        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(field.getText().isBlank() ? null : Path.of(field.getText().strip()).toFile());
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
                if (directory) {
                    loadInstances();
                }
            }
        });
        return button;
    }

    private static void row(JPanel panel, GridBagConstraints c, int y, JLabel label, JTextField field, JButton button) {
        c.gridy = y;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        panel.add(button, c);
    }

    private void log(String line) {
        log.append(line + "\n");
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(frame, message, "Lich installer", JOptionPane.ERROR_MESSAGE);
    }
}
