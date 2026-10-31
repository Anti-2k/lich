package dev.lich.installer.start;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/**
 * The installer jar's main class, compiled for Java 8 so that it loads on whatever Java opens the jar. Players often
 * double-click it with the Java 8 their old launcher installed, and Java 25 bytecode would then fail with no window
 * at all; this says which Java is needed instead, then hands over to the real installer.
 */
public final class Start {
    private static final int MIN_JAVA = 25;

    private Start() {
    }

    public static void main(String[] args) throws Exception {
        String spec = System.getProperty("java.specification.version", "1.8");
        int version = Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
        if (version < MIN_JAVA) {
            String message = "The Lich installer needs Java " + MIN_JAVA + " or newer (this is Java " + version + ").\n"
                    + "Get it from https://adoptium.net/, then run: java -jar <this file>";
            if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Lich installer", JOptionPane.ERROR_MESSAGE);
            } else {
                System.err.println(message);
            }
            System.exit(1);
        }
        Class.forName("dev.lich.installer.PrismInstaller").getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
