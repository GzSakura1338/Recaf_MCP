package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class FilesRoutes {
    private final WorkspaceManager wm;

    public FilesRoutes(WorkspaceManager wm) {
        this.wm = wm;
    }

    public void register(McpHttpServer server) {
        server.get("/files/list", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            List<String> names = ws.filesStream()
                    .map(FilePathNode::getValue)
                    .map(FileInfo::getName)
                    .sorted()
                    .toList();

            JsonObject out = JsonResponses.paginated(names, "files", req.queryInt("offset", 0), req.queryInt("limit", 0),
                    name -> {
                        JsonObject item = new JsonObject();
                        item.addProperty("path", name);
                        return item;
                    });
            res.json(out);
        });

        server.get("/files/content", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            String path = req.query("path");
            if (path == null || path.isEmpty()) {
                res.status(400).json(JsonResponses.error("missing 'path'"));
                return;
            }

            FilePathNode node = ws.findFile(path);
            if (node == null) {
                res.status(404).json(JsonResponses.error("file not found"));
                return;
            }

            FileInfo file = node.getValue();
            byte[] bytes = file.getRawContent();
            JsonObject out = new JsonObject();
            out.addProperty("path", file.getName());
            out.addProperty("size", bytes.length);

            String asText = tryDecodeUtf8(bytes);
            if (asText != null) {
                out.addProperty("encoding", "utf-8");
                out.addProperty("content", asText);
            } else {
                out.addProperty("encoding", "base64");
                out.addProperty("content", Base64.getEncoder().encodeToString(bytes));
            }
            res.json(out);
        });

        server.get("/files/manifest", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            FilePathNode node = ws.findFile("META-INF/MANIFEST.MF");
            if (node == null) {
                res.json(JsonResponses.error("no MANIFEST.MF"));
                return;
            }

            JsonObject out = new JsonObject();
            out.addProperty("path", "META-INF/MANIFEST.MF");
            out.addProperty("content", new String(node.getValue().getRawContent(), java.nio.charset.StandardCharsets.UTF_8));
            res.json(out);
        });

        server.get("/files/strings", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            List<JsonObject> strings = new ArrayList<>();
            ws.filesStream().forEach(node -> {
                FileInfo file = node.getValue();
                for (PrintableString hit : extractPrintableStrings(file.getRawContent())) {
                    JsonObject item = new JsonObject();
                    item.addProperty("path", file.getName());
                    item.addProperty("value", hit.value());
                    item.addProperty("offset", hit.offset());
                    strings.add(item);
                }
            });

            JsonObject out = JsonResponses.paginated(strings, "strings", req.queryInt("offset", 0), req.queryInt("limit", 0), item -> item);
            res.json(out);
        });
    }

    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<PrintableString> extractPrintableStrings(byte[] bytes) {
        List<PrintableString> hits = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = -1;

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            if (value >= 32 && value <= 126) {
                if (current.isEmpty()) start = i;
                current.append((char) value);
                continue;
            }
            flush(hits, current, start);
            start = -1;
        }
        flush(hits, current, start);
        return hits;
    }

    private static void flush(List<PrintableString> hits, StringBuilder current, int start) {
        if (current.length() >= 4) {
            hits.add(new PrintableString(current.toString(), start));
        }
        current.setLength(0);
    }

    private record PrintableString(String value, int offset) {
    }
}
