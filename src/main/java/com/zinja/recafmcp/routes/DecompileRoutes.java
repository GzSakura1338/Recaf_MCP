package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

public class DecompileRoutes {
    private final WorkspaceManager wm;
    private final DecompilerManager dm;
    private final DecompilerManagerConfig config;

    public DecompileRoutes(WorkspaceManager wm, DecompilerManager dm, DecompilerManagerConfig config) {
        this.wm = wm;
        this.dm = dm;
        this.config = config;
    }

    public void register(McpHttpServer server) {
        server.get("/decompilers", (req, res) -> {
            JsonArray arr = new JsonArray();
            JvmDecompiler active = dm.getTargetJvmDecompiler();
            for (JvmDecompiler decompiler : dm.getJvmDecompilers()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", decompiler.getName());
                entry.addProperty("version", decompiler.getVersion());
                entry.addProperty("active", decompiler == active);
                arr.add(entry);
            }

            JsonObject out = new JsonObject();
            out.add("decompilers", arr);
            res.json(out);
        });

        server.post("/decompilers/active", (req, res) -> {
            String name = req.bodyString("name", "");
            if (name.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'name'"));
                return;
            }

            JvmDecompiler decompiler = findDecompiler(name);
            if (decompiler == null) {
                res.status(404).json(JsonResponses.error("unknown decompiler: " + name));
                return;
            }

            config.getPreferredJvmDecompiler().setValue(decompiler.getName());
            JsonObject out = JsonResponses.ok("active decompiler updated");
            out.addProperty("name", decompiler.getName());
            res.json(out);
        });

        server.get("/class-source", (req, res) -> decompileAndRespond(req.query("class_name"), req.query("decompiler"), res));
        server.get("/decompile", (req, res) -> decompileAndRespond(req.query("class_name"), req.query("decompiler"), res));
    }

    private void decompileAndRespond(String className, String decompilerName, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) {
            res.status(409).json(JsonResponses.error("no workspace open"));
            return;
        }
        if (className == null || className.isEmpty()) {
            res.status(400).json(JsonResponses.error("missing 'class_name'"));
            return;
        }

        ClassPathNode node = ws.findJvmClass(className.replace('.', '/'));
        if (node == null) {
            res.status(404).json(JsonResponses.error("class not found: " + className));
            return;
        }

        JvmDecompiler decompiler = null;
        if (decompilerName != null && !decompilerName.isBlank()) {
            decompiler = findDecompiler(decompilerName);
            if (decompiler == null) {
                res.status(404).json(JsonResponses.error("unknown decompiler: " + decompilerName));
                return;
            }
        }

        JvmClassInfo cls = (JvmClassInfo) node.getValue();
        JvmDecompiler chosen = decompiler;
        DecompileResult result = DecompileGuard.run(cls.getName(),
                () -> chosen == null ? dm.decompile(ws, cls) : dm.decompile(chosen, ws, cls), res);
        if (result == null) {
            return;
        }

        JsonObject out = new JsonObject();
        out.addProperty("class_name", className);
        out.addProperty("decompiler", decompiler == null ? dm.getTargetJvmDecompiler().getName() : decompiler.getName());
        out.addProperty("text", result.getText());
        if (result.getException() != null) {
            out.addProperty("warning", result.getException().toString());
        }
        res.json(out);
    }

    private JvmDecompiler findDecompiler(String name) {
        JvmDecompiler exact = dm.getJvmDecompiler(name);
        if (exact != null) {
            return exact;
        }

        String needle = normalize(name);
        for (JvmDecompiler decompiler : dm.getJvmDecompilers()) {
            if (normalize(decompiler.getName()).equals(needle)) {
                return decompiler;
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[^a-zA-Z0-9]+", "").toLowerCase();
    }
}
