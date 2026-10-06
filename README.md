# XUL-J Swing bridge

`xulj-swing.jar` serves an existing, unmodified Java Swing application to browsers. It is the
Java counterpart of [xul-j/net-bridge](https://github.com/xul-j/net-bridge). It renders live Swing
windows to XUL-J ops over SSE, using the protocol and browser client of
[xul-j/xul-j](https://github.com/xul-j/xul-j), and replays browser intents as clicks and edits on
the real components, on the Event Dispatch Thread. It needs only the JDK
(`com.sun.net.httpserver`) and runs on Java 8 or later.

    # one instance of the window class per browser session (needs a no-arg constructor)
    java -Djava.security.manager=allow -jar xulj-swing.jar --jar App.jar --frame com.acme.MainFrame

    # run the app's main() once; every browser session drives that same app
    java -Djava.security.manager=allow -jar xulj-swing.jar --jar App.jar [--main com.acme.Main] [-- app args]

Options: `--port 8093`, `--host 0.0.0.0`. In shared mode, `--main` defaults to the jar's `Main-Class`.

`-Djava.security.manager=allow` lets the bridge trap `System.exit`, needed on Java 18+ and
harmless before. An app's File › Exit then ends that browser session (or, in shared mode, the
app) instead of killing the bridge. `EXIT_ON_CLOSE` frames are switched to `DISPOSE_ON_CLOSE`.

## Build and run on Linux (JDK 17 + Xvfb in Docker)

Swing needs a display, so the bridge runs under Xvfb. The build embeds the browser client from
the base repo, so clone both side by side:

    git clone https://github.com/xul-j/xul-j && git clone https://github.com/xul-j/java-bridge
    cd java-bridge
    docker build -t xulj-jdk docker
    ./build.sh                       # → out/xulj-swing.jar, out/LegacyInventory.jar (XULJ_BASE=../xul-j)
    docker run -d --init --name xulj-swing -p 8093:8093 -v "$PWD/out":/app:ro xulj-jdk \
      xvfb-run -a java -Djava.security.manager=allow -jar /app/xulj-swing.jar \
      --jar /app/LegacyInventory.jar --frame legacy.InventoryFrame --port 8093
    node test/swing-e2e.js           # end-to-end checks against the running bridge

`--init` matters: as PID 1, `xvfb-run` never sees Xvfb's ready signal and hangs silently.

## How it works

- `Renderer.java` builds a virtual XUL-J tree from the windows each frame (every 50 ms).
  - **Layout:** `BorderLayout`, `FlowLayout`, `BoxLayout`, `GridLayout` and `CardLayout` map directly.
    Everything else (`GridBagLayout`, `GroupLayout`, null layout, third-party managers) uses
    the components' real bounds, since Swing has already laid them out. Components are grouped
    into rows, each cell is sized up to the next one (keeping column alignment), and controls that
    span the container stretch. GridBag `weightx`+`fill` becomes flex and `anchor` EAST becomes
    right-aligned.
  - **Ids** come from the app's own field names (`private JButton addButton` → `addButton`),
    then component names, then button labels (`Import CSV…` → `importCsv`).
- `Reconciler.java` diffs frames into minimal ops. It is the same algorithm as the .NET bridge.
- `Host.java` holds the EDT tick, sessions, `com.sun.net.httpserver` (SSE, intents, upload and
  download), and the `System.exit` trap. An intent is validated on the EDT, which yields 404/409,
  then run with `invokeLater`, so a modal dialog never blocks HTTP. Windows the app opens are
  assigned to a session through their owner chain.
- Buttons are pressed with `doClick`, and check boxes and radio buttons are clicked rather than
  set, so `ActionListener`s run as they would for a user.

## Dialogs

No patching is needed: `JOptionPane` and `JFileChooser` are ordinary Swing components, and the
bridge drives them through their public APIs.

| App calls | Browser sees |
|---|---|
| `JOptionPane.show*Dialog` | modal window: message, icon (from the message type), input field for `showInputDialog`, the pane's own buttons, default button |
| `JFileChooser.showOpenDialog` | a file picker (`accept` from a `FileNameExtensionFilter`, multi-selection honoured). The browser uploads, the chooser's selection is set to the uploaded copy and approved |
| `JFileChooser.showSaveDialog` | a file-name prompt. The app writes to a temp path; once the file stops changing, the browser downloads it |
| any modal `JDialog` | a modal window; Escape sends `WINDOW_CLOSING`, as a window manager would |

An exception thrown by an app listener becomes an error notification. Uploads are limited to 100 MB.

## Mapping

| Swing | XUL-J |
|---|---|
| JFrame, JDialog | window (modal dialogs are `modal`) |
| JMenuBar | toolbar; leaf items are flattened to "File › Exit", accelerators become keys |
| JToolBar | toolbar (glue becomes a spacer) |
| JButton, JMenuItem | button / toolbarbutton + command (mnemonic → `alt+` key) |
| JCheckBox, JRadioButton, JToggleButton | checkbox |
| JTextField, JFormattedTextField, JPasswordField, JSpinner | textbox (`password`) |
| JTextArea, JEditorPane | textbox `multiline` |
| JComboBox | menulist |
| JTable, JList, JTree | tree with a row source (JTree rows indented by depth) |
| JProgressBar, JSlider | progressmeter |
| JTabbedPane | tabbox / tabpanel |
| JSplitPane | hbox / vbox with the divider position as size |
| JPanel with a TitledBorder | groupbox |
| JScrollPane | its view |
| anything else | a muted `[ClassName]` placeholder |

## Limitations

- AWT `FileDialog` is native and not intercepted (Swing apps rarely use it).
- Custom-painted components, images and charts do not render. Menus are flattened. Table and
  list selection, and cell editing, are not mapped yet.
- In shared mode every viewer sees, and can change, the same app, including password fields.
- Every per-session window is a live instance on the host, with no authentication and no session limit.
