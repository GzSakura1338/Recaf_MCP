package com.zinja.recafmcp.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Query-string and JSON-body accessors for an incoming request.
 */
public class Request {
    private final HttpExchange exchange;
    private Map<String, String> query;
    private JsonObject body;

    public Request(HttpExchange exchange) {
        this.exchange = exchange;
    }

    public String query(String key) {
        return query(key, null);
    }

    public String query(String key, String defaultValue) {
        if (query == null) query = parseQuery(exchange.getRequestURI());
        return query.getOrDefault(key, defaultValue);
    }

    public int queryInt(String key, int defaultValue) {
        String v = query(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean queryBool(String key, boolean defaultValue) {
        String v = query(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    public JsonObject body() throws IOException {
        if (body == null) body = parseBody();
        return body;
    }

    public String bodyString(String key, String defaultValue) throws IOException {
        JsonElement el = body().get(key);
        return (el == null || el.isJsonNull()) ? defaultValue : el.getAsString();
    }

    public int bodyInt(String key, int defaultValue) throws IOException {
        JsonElement el = body().get(key);
        return (el == null || el.isJsonNull()) ? defaultValue : el.getAsInt();
    }

    public boolean bodyBool(String key, boolean defaultValue) throws IOException {
        JsonElement el = body().get(key);
        return (el == null || el.isJsonNull()) ? defaultValue : el.getAsBoolean();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    private JsonObject parseBody() throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(reader);
            if (el == null || el.isJsonNull()) return new JsonObject();
            if (!el.isJsonObject()) throw new IOException("request body must be a JSON object");
            return el.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            // Empty body is valid for endpoints that take no arguments.
            return new JsonObject();
        }
    }
}
