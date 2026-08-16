package com.zinja.recafmcp.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mutable response wrapper — status + body helpers.
 */
public class Response {
    private static final Gson GSON = new Gson();

    private final HttpExchange exchange;
    private int status = 200;

    public Response(HttpExchange exchange) {
        this.exchange = exchange;
    }

    public Response status(int code) {
        this.status = code;
        return this;
    }

    public void json(Object payload) throws IOException {
        String body = payload instanceof JsonElement el ? GSON.toJson(el) : GSON.toJson(payload);
        write("application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public void text(String payload) throws IOException {
        write("text/plain; charset=utf-8", payload.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
