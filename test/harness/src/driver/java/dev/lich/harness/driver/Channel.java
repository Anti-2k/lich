package dev.lich.harness.driver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The driver's events, sent to the harness over a loopback socket ({@code lich.harness.port}) as one line each:
 * {@code <type> <step> [key=value ...] [-- text]}. The socket keeps working when a mod reconfigures log4j, and the
 * harness sees at once when the driver is gone. Every event but {@code at} (progress inside a step, which the harness
 * only keeps for telling where a crash happened) is also written to the game log as a {@code LICH-HARNESS} line, for
 * people reading it.
 */
final class Channel {
    static final String MARKER = "LICH-HARNESS ";
    private static final Logger LOG = LogManager.getLogger("LichHarness");

    private Writer out;

    Channel(int port) {
        if (port <= 0) {
            return;
        }
        try {
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error(MARKER + "no connection to the harness on port " + port, e);
        }
    }

    void send(String type, String step, String fields, String text) {
        StringBuilder line = new StringBuilder(type).append(' ').append(step);
        if (fields != null && !fields.isEmpty()) {
            line.append(' ').append(fields);
        }
        if (text != null) {
            line.append(" -- ").append(text.replace("\r", "").replace('\n', ' '));
        }
        String event = line.toString();
        if (!type.equals("at")) {
            if (type.equals("fail") || type.equals("problem")) {
                LOG.error(MARKER + event);
            } else {
                LOG.info(MARKER + event);
            }
        }
        synchronized (this) {
            if (out == null) {
                return;
            }
            try {
                out.write(event);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                out = null;
                LOG.error(MARKER + "lost the connection to the harness", e);
            }
        }
    }

    /** A failure's stack trace, into the game log only. */
    void trace(Throwable t) {
        LOG.error(MARKER + "stack trace", t);
    }

    /** {@code key=value} with the value made safe for the line format (no spaces). */
    static String field(String key, Object value) {
        return key + "=" + String.valueOf(value).replace(' ', '_');
    }
}
