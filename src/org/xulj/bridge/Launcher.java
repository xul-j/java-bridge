package org.xulj.bridge;

import java.awt.Window;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Serves an existing, unmodified Swing application to browsers.
 *
 *   java -Djava.security.manager=allow -jar xulj-swing.jar --jar App.jar --frame com.acme.MainFrame
 *       one instance of the window class per browser session (it needs a no-arg constructor)
 *   java -Djava.security.manager=allow -jar xulj-swing.jar --jar App.jar [--main com.acme.Main]
 *       runs the app's main() once; every browser session drives that same app (shared mode)
 *
 * Options: --port 8093  --host 0.0.0.0  -- app arguments (shared mode)
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Exception {
        String jar = arg(args, "--jar"), frame = arg(args, "--frame"), main = arg(args, "--main");
        Host.Options opt = new Host.Options();
        if (arg(args, "--port") != null) opt.port = Integer.parseInt(arg(args, "--port"));
        if (arg(args, "--host") != null) opt.host = arg(args, "--host");
        int dashdash = Arrays.asList(args).indexOf("--");
        String[] appArgs = dashdash >= 0 ? Arrays.copyOfRange(args, dashdash + 1, args.length) : new String[0];

        ClassLoader loader = Launcher.class.getClassLoader();
        if (jar != null) {
            File f = new File(jar);
            loader = new URLClassLoader(new URL[] {f.toURI().toURL()}, loader);
            if (frame == null && main == null) {
                try (JarFile jf = new JarFile(f)) {
                    Manifest m = jf.getManifest();
                    main = m == null ? null : m.getMainAttributes().getValue("Main-Class");
                }
            }
        }
        if (frame == null && main == null) {
            System.err.println("usage: xulj-swing --jar App.jar (--frame window.Class | [--main main.Class]) [--port 8093] [--host 0.0.0.0] [-- app args]");
            System.exit(2);
        }
        Thread.currentThread().setContextClassLoader(loader);

        if (frame != null) {
            Class<?> cls = Class.forName(frame, true, loader);
            if (!Window.class.isAssignableFrom(cls)) throw new IllegalArgumentException(frame + " is not a java.awt.Window");
            System.out.println("hosting " + cls.getName() + ", one instance per browser session");
            new Host(() -> {
                try {
                    return (Window) cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot create " + cls.getName(), e);
                }
            }, opt).start();
        } else {
            Host host = Host.shared(opt);
            host.start();
            Class<?> cls = Class.forName(main, true, loader);
            Method m = cls.getMethod("main", String[].class);
            System.out.println("running " + cls.getName() + ".main() once; all browser sessions share it");
            m.invoke(null, (Object) appArgs);
        }
    }

    private static String arg(String[] args, String name) {
        int i = Arrays.asList(args).indexOf(name);
        return i >= 0 && i + 1 < args.length ? args[i + 1] : null;
    }
}
