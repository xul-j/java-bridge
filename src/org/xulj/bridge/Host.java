package org.xulj.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Permission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;

/**
 * Hosts a Swing application as a web server. In per-session mode every browser session gets
 * its own instance of the app's main window; in shared mode all sessions drive one app.
 * Windows are rendered to XUL-J ops (SSE) and browser intents become clicks and edits on the
 * real components, on the Event Dispatch Thread.
 */
public final class Host {
    public static final class Options {
        public int port = 8093;
        public String host = "0.0.0.0";
        public int tickMs = 50;
        public long sessionTtlMs = 10 * 60 * 1000;
        public PrintStream log = System.out;
    }

    static final class PendingDownload {
        File file;
        String name, token;
        long lastSize = -1;
        int stableTicks;
        final long created = System.currentTimeMillis();
    }

    final class Session {
        final String id;
        final List<Window> windows = new ArrayList<>();
        final Renderer renderer = new Renderer(Host.this);
        final Reconciler reconciler = new Reconciler();
        final List<String> log = new ArrayList<>();
        final List<BlockingQueue<String>> subscribers = new ArrayList<>();
        final List<PendingDownload> pending = new ArrayList<>();
        final Map<String, PendingDownload> downloads = new ConcurrentHashMap<>();
        int seq;
        long lastSeen = System.currentTimeMillis();
        boolean ended, hadWindow;
        String themeJson;

        Session(String id) { this.id = id; }

        synchronized void emit(Map<String, Object> op) {
            op.put("seq", ++seq);
            String frame = "id: " + seq + "\ndata: " + Json.write(op) + "\n\n";
            log.add(frame);
            for (BlockingQueue<String> q : subscribers) q.offer(frame);
        }

        /** Transient ops (download, notify) reach live clients only and are never replayed. */
        synchronized void emitTransient(Map<String, Object> op) {
            String frame = "data: " + Json.write(op) + "\n\n";
            for (BlockingQueue<String> q : subscribers) q.offer(frame);
        }
    }

    /** Thrown instead of exiting the JVM when the app calls System.exit. */
    static final class ExitTrapped extends SecurityException {
        ExitTrapped(int status) { super("System.exit(" + status + ") trapped by the XUL-J bridge"); }
    }

    private final Supplier<Window> createWindow; // null in shared mode
    private final Options opt;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Window, Session> owned = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final File tempRoot;
    private Session shared;
    private Session lastActive;
    private static final long MAX_UPLOAD = 100L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Per-session mode: one window instance per browser session. */
    public Host(Supplier<Window> createWindow, Options opt) throws IOException {
        this.createWindow = createWindow;
        this.opt = opt;
        this.tempRoot = Files.createTempDirectory("xulj-bridge-").toFile();
    }

    /** Shared mode: the app has been started already; every session sees its windows. */
    public static Host shared(Options opt) throws IOException {
        Host h = new Host(null, opt);
        h.shared = h.new Session("shared");
        h.shared.emit(op("reset"));
        h.lastActive = h.shared;
        return h;
    }

    static Map<String, Object> op(String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", kind);
        return m;
    }

    @SuppressWarnings("removal")
    static void trapExit() {
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission perm) { /* allow everything */ }
                @Override public void checkPermission(Permission perm, Object context) { }
                @Override public void checkExit(int status) {
                    // Only a real exit is trapped. checkExit is also called as a permission probe,
                    // e.g. by JFrame.setDefaultCloseOperation(EXIT_ON_CLOSE), which must succeed.
                    boolean exiting = false;
                    for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                        if (e.getClassName().equals(Host.class.getName()) && e.getMethodName().equals("shutdown")) return;
                        if (e.getClassName().equals("java.lang.Runtime") && (e.getMethodName().equals("exit") || e.getMethodName().equals("halt"))) exiting = true;
                    }
                    if (exiting) throw new ExitTrapped(status);
                }
            });
        } catch (UnsupportedOperationException | SecurityException e) {
            System.err.println("warning: cannot trap System.exit (run with -Djava.security.manager=allow): " + e);
        }
    }

    static void shutdown(int status) { System.exit(status); }

    public void start() throws IOException {
        trapExit();
        SwingUtilities.invokeLater(() -> new Timer(opt.tickMs, e -> tick()).start());
        HttpServer server = HttpServer.create(new InetSocketAddress(opt.host, opt.port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "xulj-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        opt.log.println("XUL-J Swing bridge serving on http://" + opt.host + ":" + opt.port + "/" + (shared != null ? " (shared mode)" : ""));
    }

    // ---- EDT ----------------------------------------------------------------------------------

    private Session sessionFor(Window w) {
        for (Window p = w; p != null; p = p.getOwner()) {
            Session s = owned.get(p);
            if (s != null) return s;
        }
        return shared != null ? shared : lastActive;
    }

    private void tick() {
        // Adopt windows the app opened (dialogs, more frames): the owner chain says whose they are.
        for (Window w : Window.getWindows()) {
            if (owned.containsKey(w) || !w.isShowing() || !(w instanceof Frame || w instanceof Dialog)) continue;
            Session s = sessionFor(w);
            if (s == null || s.ended) continue;
            owned.put(w, s);
            s.windows.add(w);
            s.hadWindow = true;
            neverExit(w);
        }
        List<Session> all = new ArrayList<>(sessions.values());
        if (shared != null) all.add(shared);
        for (Session s : all) {
            if (s.ended) continue;
            boolean idle;
            synchronized (s) { idle = s != shared && s.subscribers.isEmpty() && System.currentTimeMillis() - s.lastSeen > opt.sessionTtlMs; }
            if (idle) { end(s, "session " + s.id + " expired"); continue; }
            // Hidden secondary windows (closed dialogs) are released; the first window is the app.
            for (Window w : new ArrayList<>(s.windows)) {
                boolean primary = s.windows.indexOf(w) == 0 && shared == null;
                if (!w.isDisplayable() || !primary && !w.isVisible()) { s.windows.remove(w); owned.remove(w); }
            }
            if (s.windows.isEmpty() || shared == null && !s.windows.get(0).isDisplayable()) {
                if (shared != null && !s.hadWindow) continue; // shared app still starting up
                endWithMessage(s, shared != null ? "The application has exited."
                    : "The application closed this window. Reload to start a new session.");
                continue;
            }
            checkDownloads(s);
            try {
                s.renderer.render(s.windows);
                Map<String, Object> theme = s.renderer.theme();
                String themeJson = theme == null ? null : Json.write(theme);
                if (themeJson != null && !themeJson.equals(s.themeJson)) {
                    s.themeJson = themeJson;
                    Map<String, Object> o = op("theme");
                    o.put("tokens", theme);
                    if (isDark(String.valueOf(theme.get("background")))) o.put("dark", theme); // a dark app stays dark
                    s.emit(o);
                }
                for (Map<String, Object> o : s.reconciler.diff(s.renderer.nodes(), s.renderer.commands())) s.emit(o);
            } catch (RuntimeException e) {
                e.printStackTrace(opt.log);
            }
        }
    }

    private static boolean isDark(String hex) {
        if (hex == null || !hex.matches("^#[0-9a-f]{6}$")) return false;
        int r = Integer.parseInt(hex.substring(1, 3), 16), g = Integer.parseInt(hex.substring(3, 5), 16), b = Integer.parseInt(hex.substring(5, 7), 16);
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.35;
    }

    private static int rowCount(Object c) {
        if (c instanceof javax.swing.JTable) return ((javax.swing.JTable) c).getRowCount();
        if (c instanceof javax.swing.JList) return ((javax.swing.JList<?>) c).getModel().getSize();
        if (c instanceof javax.swing.JTree) return ((javax.swing.JTree) c).getRowCount();
        return 0;
    }

    /** Selecting rows the way a user would, so selection listeners fire. */
    private static void selectRows(Object c, int[] rows) {
        if (c instanceof javax.swing.JTable) {
            javax.swing.JTable t = (javax.swing.JTable) c;
            t.clearSelection();
            for (int r : rows) t.addRowSelectionInterval(r, r);
        } else if (c instanceof javax.swing.JList) {
            ((javax.swing.JList<?>) c).setSelectedIndices(rows);
        } else if (c instanceof javax.swing.JTree) {
            ((javax.swing.JTree) c).setSelectionRows(rows);
        }
    }

    /** Double-click: Swing apps listen for a MouseEvent with clickCount 2, so that is what they get. */
    private static void activate(Object c, int row) {
        selectRows(c, new int[] {row});
        java.awt.Rectangle r = null;
        if (c instanceof javax.swing.JTable) r = ((javax.swing.JTable) c).getCellRect(row, 0, true);
        else if (c instanceof javax.swing.JList) r = ((javax.swing.JList<?>) c).getCellBounds(row, row);
        else if (c instanceof javax.swing.JTree) r = ((javax.swing.JTree) c).getRowBounds(row);
        if (r == null) return;
        java.awt.Component comp = (java.awt.Component) c;
        int x = r.x + Math.min(8, Math.max(1, r.width / 2)), y = r.y + r.height / 2;
        long when = System.currentTimeMillis();
        for (int id : new int[] {java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_RELEASED, java.awt.event.MouseEvent.MOUSE_CLICKED}) {
            comp.dispatchEvent(new java.awt.event.MouseEvent(comp, id, when, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 2, false, java.awt.event.MouseEvent.BUTTON1));
        }
    }

    /** Runs the app's popup listeners as if the menu were opening over `source`. */
    private static void openContextMenu(javax.swing.JPopupMenu pm, javax.swing.JComponent source) {
        pm.setInvoker(source);
        javax.swing.event.PopupMenuEvent ev = new javax.swing.event.PopupMenuEvent(pm);
        for (javax.swing.event.PopupMenuListener l : pm.getPopupMenuListeners()) l.popupMenuWillBecomeVisible(ev);
    }

    private static void neverExit(Window w) {
        if (w instanceof JFrame && ((JFrame) w).getDefaultCloseOperation() == WindowConstants.EXIT_ON_CLOSE) {
            ((JFrame) w).setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        }
    }

    private void endWithMessage(Session s, String message) {
        s.ended = true;
        s.emit(op("reset"));
        Map<String, Object> o = op("node");
        o.put("in", "root");
        o.put("tag", "description");
        o.put("id", "ended");
        o.put("value", message);
        o.put("class", "muted");
        s.emit(o);
    }

    private void end(Session s, String why) {
        s.ended = true;
        sessions.remove(s.id);
        for (Window w : new ArrayList<>(s.windows)) { w.dispose(); owned.remove(w); }
        opt.log.println(why);
    }

    private Session createSession(String id) {
        Session s = new Session(id);
        s.emit(op("reset"));
        Window w = createWindow.get();
        neverExit(w);
        w.setLocation(-32000, -32000);
        owned.put(w, s);
        s.windows.add(w);
        lastActive = s;
        if (!w.isVisible()) w.setVisible(true);
        return s;
    }

    // Called on the EDT by Renderer when the app saves through a JFileChooser.
    File expectDownload(String name) {
        PendingDownload d = new PendingDownload();
        d.token = token();
        d.name = name;
        File dir = new File(new File(tempRoot, "down"), d.token);
        dir.mkdirs();
        d.file = new File(dir, name);
        if (lastActive != null) lastActive.pending.add(d);
        return d.file;
    }

    private static void checkDownloads(Session s) {
        for (PendingDownload d : new ArrayList<>(s.pending)) {
            if (!d.file.exists()) {
                if (System.currentTimeMillis() - d.created > 10 * 60 * 1000) s.pending.remove(d);
                continue;
            }
            long size = d.file.length();
            d.stableTicks = size == d.lastSize ? d.stableTicks + 1 : 0;
            d.lastSize = size;
            if (d.stableTicks < 10) continue; // unchanged for ~500 ms
            s.pending.remove(d);
            s.downloads.put(d.token, d);
            Map<String, Object> o = op("download");
            o.put("url", "/download/" + d.token);
            o.put("name", d.name);
            s.emitTransient(o);
        }
    }

    static String token() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String safeName(String name) {
        if (name == null) return "";
        name = new File(name.trim()).getName().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "");
        return name.replaceAll("^[. ]+|[. ]+$", "");
    }

    /**
     * Validates an intent on the EDT (so HTTP can answer 404/409) and returns the action to run
     * afterwards with invokeLater: a modal dialog opened by the action must not block HTTP.
     */
    private int prepare(Session s, Map<String, Object> msg, AtomicReference<Runnable> action) {
        Object op = msg.get("op");
        if ("do".equals(op)) {
            Object cmd = msg.get("command");
            if (!(cmd instanceof String)) return 400;
            Map<String, Object> state = s.renderer.commands().get(cmd);
            if (state == null) return 404;
            if (Boolean.TRUE.equals(state.get("disabled"))) return 409;
            Runnable synthetic = s.renderer.click((String) cmd);
            if (synthetic != null) { action.set(synthetic); return 202; }
            Object target = s.renderer.lookup(((String) cmd).substring(4));
            if (!(target instanceof AbstractButton)) return 404;
            action.set(() -> ((AbstractButton) target).doClick(0));
            return 202;
        }
        if ("select".equals(op) || "activate".equals(op)) {
            Object id = msg.get("id");
            Object target = id instanceof String ? s.renderer.lookup((String) id) : null;
            if (!(target instanceof javax.swing.JTable || target instanceof javax.swing.JList || target instanceof javax.swing.JTree)) return 404;
            if (!((java.awt.Component) target).isEnabled()) return 409;
            int count = rowCount(target);
            if ("activate".equals(op)) {
                Object rv = msg.get("row");
                if (!(rv instanceof Double) || (Double) rv < 0 || (Double) rv >= count || (Double) rv % 1 != 0) return 400;
                int row = ((Double) rv).intValue();
                action.set(() -> activate(target, row));
                return 202;
            }
            Object rl = msg.get("rows");
            if (!(rl instanceof List)) return 400;
            List<?> list = (List<?>) rl;
            int[] rows = new int[list.size()];
            for (int i = 0; i < rows.length; i++) {
                Object x = list.get(i);
                if (!(x instanceof Double) || (Double) x < 0 || (Double) x >= count || (Double) x % 1 != 0) return 400;
                rows[i] = ((Double) x).intValue();
            }
            action.set(() -> selectRows(target, rows));
            return 202;
        }
        if ("contextmenu".equals(op)) {
            Object pid = msg.get("id"), tid = msg.get("target");
            Object pm = pid instanceof String ? s.renderer.lookup((String) pid) : null;
            Object src = tid instanceof String ? s.renderer.lookup((String) tid) : null;
            if (!(pm instanceof javax.swing.JPopupMenu) || !(src instanceof javax.swing.JComponent)
                || ((javax.swing.JComponent) src).getComponentPopupMenu() != pm) return 404;
            action.set(() -> openContextMenu((javax.swing.JPopupMenu) pm, (javax.swing.JComponent) src));
            return 202;
        }
        if ("input".equals(op)) {
            Object id = msg.get("id");
            Object value = msg.get("value");
            if (!(id instanceof String)) return 400;
            Consumer<Object> synthetic = s.renderer.input((String) id);
            if (synthetic != null) { action.set(() -> synthetic.accept(value)); return 202; }
            Object target = s.renderer.lookup((String) id);
            if (!(target instanceof java.awt.Component)) return 404;
            if (!((java.awt.Component) target).isEnabled()) return 409;
            if (target instanceof JTextComponent) {
                if (!((JTextComponent) target).isEditable()) return 409;
                action.set(() -> ((JTextComponent) target).setText(String.valueOf(value)));
            } else if (target instanceof JToggleButton) {
                JToggleButton t = (JToggleButton) target;
                // A real click (not setSelected) so ActionListeners run, as for a user.
                action.set(() -> { if (t.isSelected() != Boolean.TRUE.equals(value)) t.doClick(0); });
            } else if (target instanceof JComboBox) {
                int idx;
                try { idx = Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 400; }
                JComboBox<?> combo = (JComboBox<?>) target;
                if (idx < 0 || idx >= combo.getItemCount()) return 400;
                action.set(() -> combo.setSelectedIndex(idx));
            } else if (target instanceof JSpinner) {
                JSpinner sp = (JSpinner) target;
                action.set(() -> {
                    try {
                        Object v = value;
                        if (sp.getModel() instanceof SpinnerNumberModel) {
                            Number cur = (Number) sp.getValue();
                            double d = Double.parseDouble(String.valueOf(value));
                            // Not a ?: chain: mixed boxed types there would all be promoted to Double.
                            if (cur instanceof Integer) v = Integer.valueOf((int) d);
                            else if (cur instanceof Long) v = Long.valueOf((long) d);
                            else v = Double.valueOf(d);
                        }
                        sp.setValue(v);
                    } catch (IllegalArgumentException ignored) { /* invalid value: keep the old one */ }
                });
            } else return 409;
            return 202;
        }
        return 400;
    }

    private void run(Session s, Runnable action) {
        lastActive = s;
        try {
            action.run();
        } catch (ExitTrapped e) {
            opt.log.println("app called System.exit in session " + s.id + "; closing its windows");
            for (Window w : new ArrayList<>(s.windows)) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
            for (Window w : new ArrayList<>(s.windows)) w.dispose();
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            final Throwable cause = root;
            if (cause instanceof ExitTrapped) { run(s, () -> { throw (ExitTrapped) cause; }); return; }
            e.printStackTrace(opt.log);
            Map<String, Object> o = op("notify");
            o.put("level", "error");
            o.put("message", "The application raised an error: " + cause);
            s.emitTransient(o);
        }
    }

    private <T> T onEdt(Supplier<T> f) {
        AtomicReference<T> out = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> out.set(f.get()));
        } catch (java.lang.reflect.InvocationTargetException e) {
            // An error inside the app or the renderer: report it (500), unlike a dropped client.
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out.get();
    }

    // ---- HTTP ---------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && path.equals("/stream")) stream(ex);
            else if (method.equals("POST") && path.equals("/intent")) intent(ex);
            else if (method.equals("POST") && path.equals("/upload")) upload(ex);
            else if (method.equals("GET") && path.startsWith("/download/")) download(ex, path.substring(10));
            else if (method.equals("GET")) resource(ex, path.equals("/") ? "/index.html" : path);
            else text(ex, 405, "method not allowed");
        } catch (IOException e) {
            // client went away
        } catch (RuntimeException e) {
            e.printStackTrace(opt.log);
            try { text(ex, 500, "error"); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            try {
                if (i > 0) q.put(URLDecoder.decode(part.substring(0, i), "UTF-8"), URLDecoder.decode(part.substring(i + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) { }
        }
        return q;
    }

    private Session session(HttpExchange ex, boolean create) throws IOException {
        if (shared != null) return shared;
        String id = query(ex).get("session");
        if (id == null || !id.matches("^[A-Za-z0-9_-]{4,64}$")) return null;
        synchronized (sessions) {
            Session s = sessions.get(id);
            if ((s == null || s.ended) && create) {
                s = onEdt(() -> createSession(id));
                sessions.put(id, s);
            }
            return s;
        }
    }

    private void stream(HttpExchange ex) throws IOException {
        Session s = session(ex, true);
        if (s == null) { text(ex, 400, "bad session"); return; }
        int from = 0;
        String last = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (last == null) last = query(ex).get("from");
        try { if (last != null) from = Integer.parseInt(last); } catch (NumberFormatException ignored) { }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        synchronized (s) {
            int start = from > s.seq ? 0 : from;
            q.offer("retry: 1000\n\n");
            for (int i = start; i < s.log.size(); i++) q.offer(s.log.get(i));
            s.subscribers.add(q);
        }
        OutputStream out = ex.getResponseBody();
        try {
            while (true) {
                String frame = q.poll(15, TimeUnit.SECONDS);
                if (frame == null) frame = ": ping\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // client went away
        } finally {
            synchronized (s) { s.subscribers.remove(q); s.lastSeen = System.currentTimeMillis(); }
        }
    }

    private void intent(HttpExchange ex) throws IOException {
        Session s = session(ex, false);
        if (s == null || s.ended) { text(ex, 404, "no such session"); return; }
        byte[] body = readAll(ex.getRequestBody(), 65536);
        Object parsed;
        try { parsed = body == null ? null : Json.parse(new String(body, StandardCharsets.UTF_8)); } catch (RuntimeException e) { parsed = null; }
        if (!(parsed instanceof Map)) { text(ex, 400, "bad json"); return; }
        @SuppressWarnings("unchecked") Map<String, Object> msg = (Map<String, Object>) parsed;
        AtomicReference<Runnable> action = new AtomicReference<>();
        int status = onEdt(() -> prepare(s, msg, action));
        if (status == 202) SwingUtilities.invokeLater(() -> run(s, action.get()));
        synchronized (s) { s.lastSeen = System.currentTimeMillis(); }
        text(ex, status, status == 202 ? "ok" : status == 409 ? "command disabled" : status == 404 ? "unknown target" : "bad intent");
    }

    // Browser → app: the file lands in a temp folder and is handed to the JFileChooser.
    private void upload(HttpExchange ex) throws IOException {
        Session s = session(ex, false);
        if (s == null || s.ended) { text(ex, 404, "no such session"); return; }
        String id = query(ex).getOrDefault("id", "");
        String header = ex.getRequestHeaders().getFirst("X-Filename");
        String name = safeName(header == null ? "" : URLDecoder.decode(header, "UTF-8"));
        if (name.isEmpty()) name = "upload";
        JFileChooser fc = onEdt(() -> s.renderer.picker(id));
        if (fc == null) { text(ex, 404, "no file picker " + id); return; }
        File dir = new File(new File(new File(tempRoot, "up"), s.id), token());
        dir.mkdirs();
        File file = new File(dir, name);
        long total = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(file.toPath())) {
            byte[] buf = new byte[81920];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_UPLOAD) break;
                out.write(buf, 0, n);
            }
        }
        if (total > MAX_UPLOAD) { file.delete(); text(ex, 413, "file too large"); return; }
        SwingUtilities.invokeLater(() -> s.renderer.addUpload(fc, file));
        text(ex, 202, "ok");
    }

    // App → browser: a file the app saved through a JFileChooser.
    private void download(HttpExchange ex, String token) throws IOException {
        PendingDownload d = null;
        List<Session> all = new ArrayList<>(sessions.values());
        if (shared != null) all.add(shared);
        for (Session s : all) if ((d = s.downloads.get(token)) != null) break;
        if (d == null || !d.file.exists()) { text(ex, 404, "not found"); return; }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        String ascii = d.name.replaceAll("[^A-Za-z0-9._ -]", "_");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
            + java.net.URLEncoder.encode(d.name, "UTF-8").replace("+", "%20"));
        ex.sendResponseHeaders(200, d.file.length());
        try (OutputStream out = ex.getResponseBody()) { Files.copy(d.file.toPath(), out); }
    }

    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("json", "application/json");
    }

    private void resource(HttpExchange ex, String path) throws IOException {
        if (!path.matches("^/[A-Za-z0-9_.-]+$")) { text(ex, 404, "not found"); return; }
        try (InputStream in = Host.class.getResourceAsStream("/public" + path)) {
            if (in == null) { text(ex, 404, "not found"); return; }
            byte[] data = readAll(in, Integer.MAX_VALUE);
            String ext = path.substring(path.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(data); }
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) return null;
        }
        return bos.toByteArray();
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }
}
