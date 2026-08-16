package com.zinja.recafmcp.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal JDK-only HTTP server + path router.
 * <p>
 * Kept intentionally small so the plugin has no external HTTP dependency — the
 * only third-party dep the plugin relies on is Gson, which Recaf already
 * bundles. Each route is a {@link Handler} that takes a {@link Request} /
 * {@link Response} pair and writes a response body.
 */
public class McpHttpServer {
    private static final Logger LOG = Logger.getLogger(McpHttpServer.class.getName());

    private final String host;
    private final int port;
    private final Map<String, Handler> getRoutes = new HashMap<>();
    private final Map<String, Handler> postRoutes = new HashMap<>();

    private HttpServer underlying;

    public McpHttpServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void get(String path, Handler handler) {
        getRoutes.put(normalize(path), handler);
    }

    public void post(String path, Handler handler) {
        postRoutes.put(normalize(path), handler);
    }

    public void start() throws IOException {
        underlying = HttpServer.create(new InetSocketAddress(host, port), 0);
        underlying.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "recaf-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        underlying.createContext("/", this::dispatch);
        underlying.start();
    }

    public void stop() {
        if (underlying != null) {
            underlying.stop(0);
            underlying = null;
        }
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
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Route " + method + " " + path + " failed", e);
            try {
                res.status(500).json(JsonResponses.error(e.getClass().getSimpleName() + ": " + e.getMessage()));
            } catch (IOException ignored) {
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
