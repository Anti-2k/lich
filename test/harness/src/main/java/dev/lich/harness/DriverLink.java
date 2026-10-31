package dev.lich.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The harness's end of the driver's channel: a loopback socket the game connects to, whose lines become
 * {@link Event}s. Every connection is read (a relaunching mod could connect twice; there is still one driver).
 */
final class DriverLink implements AutoCloseable {
    private final ServerSocket socket;
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

    DriverLink() throws IOException {
        socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::accept, "harness-driver-link");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    /** The events that came since the last call. */
    List<Event> drain() {
        List<Event> out = new ArrayList<>();
        events.drainTo(out);
        return out;
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                Thread reader = new Thread(() -> read(connection), "harness-driver-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void read(Socket connection) {
        try (connection; BufferedReader in = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                Event event = Event.parse(line);
                if (event != null) {
                    events.add(event);
                }
            }
        } catch (IOException ignored) {
            // The game exited.
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
