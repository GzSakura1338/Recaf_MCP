package com.zinja.recafmcp.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal JDK-only HTTP server + path router.
 * <p>
 * Kept intentionally small so the plugin has no external HTTP dependency — the
 * only third-party dep the plugin relies on is Gson, which Recaf already
 * bundles. Each route is a {@link Handler} that takes a {@link Request} /
 * {@link Response} pair and writes a response body.
 * <p>
 * A watchdog probes the server and rebinds it when it stops answering, because
 * the JDK server's dispatcher thread dies for good on an {@link Error} such as
 * an out-of-memory in Recaf and leaves the port listening with nobody serving.
 */
public class McpHttpServer {
    private static final Logger LOG = Logger.getLogger(McpHttpServer.class.getName());
    private static final int WORKER_THREADS = 4;
    private static final long WATCHDOG_INTERVAL_SECONDS = 10;
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int FAILED_PROBES_BEFORE_RESTART = 2;
    private static final int REBIND_ATTEMPTS = 20;
    private static final long STOP_WAIT_MS = 5000;

    private final String host;
    private final Map<String, Handler> getRoutes = new HashMap<>();
    private final Map<String, Handler> postRoutes = new HashMap<>();
    private final ThreadPoolExecutor workers = newWorkers();

    private volatile int port;
    private volatile boolean running;
    private HttpServer underlying;
    private ScheduledExecutorService watchdog;
    private int failedProbes;

    public McpHttpServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public void get(String path, Handler handler) {
        getRoutes.put(normalize(path), handler);
    }

    public void post(String path, Handler handler) {
        postRoutes.put(normalize(path), handler);
    }

    public synchronized void start(int portSpan, Set<Integer> reservedPorts) throws IOException {
        int firstPort = port;
        HttpServer server = null;
        for (int offset = 0; offset < portSpan && server == null; offset++) {
            int candidate = firstPort + offset;
            if (reservedPorts.contains(candidate)) continue;
            try {
                server = HttpServer.create(new InetSocketAddress(host, candidate), 0);
                port = candidate;
            } catch (BindException ignored) {
            }
        }
        if (server == null) {
            throw new BindException("no free port in " + firstPort + ".." + (firstPort + portSpan - 1));
        }
        serve(server);

        running = true;
        failedProbes = 0;
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "recaf-mcp-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::checkHealth, WATCHDOG_INTERVAL_SECONDS,
                WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        if (underlying != null) {
            underlying.stop(0);
            underlying = null;
        }
    }

    private void serve(HttpServer server) {
        server.setExecutor(workers);
        server.createContext("/", this::dispatch);
        server.start();
        underlying = server;
    }

    private static ThreadPoolExecutor newWorkers() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "recaf-mcp-http");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private void checkHealth() {
        try {
            if (!running) return;
            if (underlying != null) {
                if (probe()) {
                    failedProbes = 0;
                    return;
                }
                // All workers busy means requests are slow, not that the dispatcher died.
                if (workers.getActiveCount() >= WORKER_THREADS) return;
                if (++failedProbes < FAILED_PROBES_BEFORE_RESTART) return;
            }
            failedProbes = 0;
            restart();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Recaf MCP HTTP watchdog check failed", t);
        }
    }

    private boolean probe() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://" + host + ":" + port + "/health")
                    .toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.getResponseCode();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void restart() throws InterruptedException {
        if (!running) return;
        LOG.warning("Recaf MCP HTTP server is not answering; rebinding port " + port);

        HttpServer old = underlying;
        underlying = null;
        if (old != null) {
            Thread stopper = new Thread(() -> old.stop(0), "recaf-mcp-stop");
            stopper.setDaemon(true);
            stopper.start();
            stopper.join(STOP_WAIT_MS);
        }

        for (int attempt = 0; attempt < REBIND_ATTEMPTS; attempt++) {
            try {
                serve(HttpServer.create(new InetSocketAddress(host, port), 0));
                LOG.info("Recaf MCP HTTP server restarted on http://" + host + ":" + port);
                return;
            } catch (BindException e) {
                Thread.sleep(250);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not restart Recaf MCP HTTP server", e);
                return;
            }
        }
        LOG.severe("Could not rebind port " + port + "; will retry");
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = normalize(exchange.getRequestURI().getPath());
        Map<String, Handler> table = switch (method) {
            case "GET" -> getRoutes;
            case "POST" -> postRoutes;
            default -> null;
        };

        Request req = new Request(exchange);
        Response res = new Response(exchange);

        try {
            if (table == null) {
                res.status(405).json(JsonResponses.error("method not allowed"));
                return;
            }
            Handler handler = table.get(path);
            if (handler == null) {
                res.status(404).json(JsonResponses.error("no route for " + method + " " + path));
                return;
            }
            handler.handle(req, res);
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "Route " + method + " " + path + " failed", e);
            boolean outOfMemory = e instanceof OutOfMemoryError;
            String message = outOfMemory
                    ? "Recaf ran out of heap memory; restart it with a larger -Xmx"
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            try {
                res.status(outOfMemory ? 503 : 500).json(JsonResponses.error(message));
            } catch (Throwable ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }
}
