package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceCompressType;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BytecodeEditRoutes {
    private static final Logger LOG = Logger.getLogger(BytecodeEditRoutes.class.getName());
    private final WorkspaceManager wm;

    public BytecodeEditRoutes(WorkspaceManager wm) {
        this.wm = wm;
        LOG.log(Level.INFO, "BytecodeEditRoutes constructed");
    }

    public void register(McpHttpServer server) {
        LOG.log(Level.INFO, "BytecodeEditRoutes.register() starting, registering 16 routes");
        server.get("/__bytecode_edit_diag__", (req, res) -> res.text("ok"));

        // ── list instructions ────────────────────────────────────────
        server.get("/method/instructions", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(req.query("class_name"),
                    req.query("method_name"), req.query("descriptor"), res);
            if (target == null) return;
            ClassNode cn = BytecodeEditSupport.readClassNode(target.classInfo());
            for (MethodNode m : cn.methods) {
                if (!m.name.equals(target.name()) || !m.desc.equals(target.descriptor())) continue;
                JsonArray insns = BytecodeEditSupport.instructionsToJson(m);
                JsonObject out = new JsonObject();
                out.addProperty("class_name", target.classInfo().getName().replace('/', '.'));
                out.addProperty("method_name", target.name());
                out.addProperty("descriptor", target.descriptor());
                out.addProperty("instruction_count", insns.size());
                out.add("instructions", insns);
                res.json(out);
                return;
            }
            res.status(404).json(JsonResponses.error("method not found in bytecode"));
        });

        // ── replace method body (JSON) ──────────────────────────────
        server.post("/method/replace-body", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(
                    req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                    req.bodyString("descriptor", ""), res);
            if (target == null) return;
            JsonArray insns = req.body().getAsJsonArray("instructions");
            if (insns == null || insns.isEmpty()) {
                res.status(400).json(JsonResponses.error("missing 'instructions' array"));
                return;
            }
            try {
                int ok = BytecodeEditSupport.replaceMethodBody(wm.getCurrent(), target, insns);
                if (ok == 0) {
                    res.status(500).json(JsonResponses.error("failed to update method body"));
                    return;
                }
                JsonObject out = JsonResponses.ok("method body replaced");
                out.addProperty("affected_classes", 1);
                out.addProperty("instruction_count", insns.size());
                res.json(out);
            } catch (IllegalArgumentException e) {
                res.status(400).json(JsonResponses.error(e.getMessage()));
            }
        });

        // ── assemble method (JASM text) ─────────────────────────────
        server.post("/method/assemble", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(
                    req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                    req.bodyString("descriptor", ""), res);
            if (target == null) return;
            String text = req.bodyString("text", "");
            if (text.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'text' (JASM body)"));
                return;
            }
            try {
                int ok = BytecodeEditSupport.assembleMethodBody(wm.getCurrent(), target, text);
                if (ok == 0) {
                    res.status(500).json(JsonResponses.error("failed to assemble method"));
                    return;
                }
                res.json(JsonResponses.ok("method assembled"));
            } catch (IllegalArgumentException e) {
                res.status(400).json(JsonResponses.error(e.getMessage()));
            }
        });

        // ── single instruction ops ─────────────────────────────────
        server.post("/method/instructions/replace", (req, res) ->
                singleInsnOp(req, res, "replace"));
        server.post("/method/instructions/insert", (req, res) ->
                singleInsnOp(req, res, "insert"));
        server.post("/method/instructions/remove", (req, res) ->
                singleInsnOp(req, res, "remove"));

        // ── edit access flags ──────────────────────────────────────
        server.post("/class/edit-access", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String name = req.bodyString("class_name", "");
            if (name.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name'"));
                return;
            }
            List<String> setF = toList(req.body().getAsJsonArray("set_flags"));
            List<String> clearF = toList(req.body().getAsJsonArray("clear_flags"));
            int ok = BytecodeEditSupport.editClassAccess(ws, name, setF, clearF);
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("class not found"));
                return;
            }
            res.json(JsonResponses.ok("class access flags updated"));
        });

        server.post("/method/edit-access", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(
                    req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                    req.bodyString("descriptor", ""), res);
            if (target == null) return;
            List<String> setF = toList(req.body().getAsJsonArray("set_flags"));
            List<String> clearF = toList(req.body().getAsJsonArray("clear_flags"));
            int ok = BytecodeEditSupport.editMethodAccess(wm.getCurrent(), target, setF, clearF);
            if (ok == 0) {
                res.status(500).json(JsonResponses.error("failed to update method access"));
                return;
            }
            res.json(JsonResponses.ok("method access flags updated"));
        });

        server.post("/field/edit-access", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String cls = req.bodyString("class_name", "");
            String fn = req.bodyString("field_name", "");
            String fd = req.bodyString("descriptor", "");
            if (cls.isBlank() || fn.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name' or 'field_name'"));
                return;
            }
            List<String> setF = toList(req.body().getAsJsonArray("set_flags"));
            List<String> clearF = toList(req.body().getAsJsonArray("clear_flags"));
            int ok = BytecodeEditSupport.editFieldAccess(ws, cls, fn, fd, setF, clearF);
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("field not found"));
                return;
            }
            res.json(JsonResponses.ok("field access flags updated"));
        });

        // ── replace class bytes ────────────────────────────────────
        server.post("/class/replace-bytes", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String name = req.bodyString("class_name", "");
            String b64 = req.bodyString("bytes_base64", "");
            if (name.isBlank() || b64.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name' or 'bytes_base64'"));
                return;
            }
            byte[] bytes = Base64.getDecoder().decode(b64);
            int ok = BytecodeEditSupport.replaceClassBytes(ws, name, bytes);
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("class not found in workspace"));
                return;
            }
            res.json(JsonResponses.ok("class bytes replaced"));
        });

        // ── add / remove methods ───────────────────────────────────
        server.post("/method/add", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String cls = req.bodyString("class_name", "");
            String mn = req.bodyString("method_name", "");
            String md = req.bodyString("descriptor", "");
            if (cls.isBlank() || mn.isBlank() || md.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name', 'method_name', or 'descriptor'"));
                return;
            }
            int acc = flagsFromJson(req.body().getAsJsonArray("access"));
            JsonArray insns = req.body().getAsJsonArray("instructions");
            int ok = BytecodeEditSupport.addMethod(ws, cls, mn, md, acc, insns);
            if (ok < 0) {
                res.status(409).json(JsonResponses.error("method already exists"));
                return;
            }
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("class not found"));
                return;
            }
            res.json(JsonResponses.ok("method added"));
        });

        server.post("/method/remove", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(
                    req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                    req.bodyString("descriptor", ""), res);
            if (target == null) return;
            int ok = BytecodeEditSupport.removeMethod(wm.getCurrent(), target);
            if (ok == 0) {
                res.status(500).json(JsonResponses.error("failed to remove method"));
                return;
            }
            res.json(JsonResponses.ok("method removed"));
        });

        // ── add / remove fields ────────────────────────────────────
        server.post("/field/add", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String cls = req.bodyString("class_name", "");
            String fn = req.bodyString("field_name", "");
            String fd = req.bodyString("descriptor", "");
            if (cls.isBlank() || fn.isBlank() || fd.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name', 'field_name', or 'descriptor'"));
                return;
            }
            int acc = flagsFromJson(req.body().getAsJsonArray("access"));
            String sig = req.bodyString("signature", null);
            Object val = fieldValue(req.body().get("value"));
            int ok = BytecodeEditSupport.addField(ws, cls, fn, fd, acc, sig, val);
            if (ok < 0) {
                res.status(409).json(JsonResponses.error("field already exists"));
                return;
            }
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("class not found"));
                return;
            }
            res.json(JsonResponses.ok("field added"));
        });

        server.post("/field/remove", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String cls = req.bodyString("class_name", "");
            String fn = req.bodyString("field_name", "");
            String fd = req.bodyString("descriptor", "");
            if (cls.isBlank() || fn.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'class_name' or 'field_name'"));
                return;
            }
            int ok = BytecodeEditSupport.removeField(ws, cls, fn, fd);
            if (ok == 0) {
                res.status(404).json(JsonResponses.error("field not found"));
                return;
            }
            res.json(JsonResponses.ok("field removed"));
        });

        // ── try-catch blocks ───────────────────────────────────────
        server.post("/method/try-catch", (req, res) -> {
            RouteSupport.MethodTarget target = resolveMethod(
                    req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                    req.bodyString("descriptor", ""), res);
            if (target == null) return;
            JsonArray tc = req.body().getAsJsonArray("try_catch_blocks");
            if (tc == null) {
                res.status(400).json(JsonResponses.error("missing 'try_catch_blocks' array"));
                return;
            }
            int ok = BytecodeEditSupport.setTryCatchBlocks(wm.getCurrent(), target, tc);
            if (ok == 0) {
                res.status(500).json(JsonResponses.error("failed to set try-catch blocks"));
                return;
            }
            res.json(JsonResponses.ok("try-catch blocks updated"));
        });

        // ── save workspace ─────────────────────────────────────────
        server.post("/workspace/save", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) return;
            String outputPath = req.bodyString("output_path", "");
            if (outputPath.isBlank()) {
                res.status(400).json(JsonResponses.error("missing 'output_path'"));
                return;
            }
            Path path = Paths.get(outputPath).toAbsolutePath().normalize();
            WorkspaceOutputType outType;
            if (Files.isDirectory(path)) outType = WorkspaceOutputType.DIRECTORY;
            else {
                String fname = path.getFileName() == null ? "" : path.getFileName().toString();
                outType = fname.contains(".") ? WorkspaceOutputType.FILE : WorkspaceOutputType.DIRECTORY;
            }
            if (outType == WorkspaceOutputType.DIRECTORY) Files.createDirectories(path);
            else {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
            }

            WorkspaceExportOptions opts = new WorkspaceExportOptions(
                    WorkspaceCompressType.MATCH_ORIGINAL, outType,
                    new PathWorkspaceExportConsumer(path));
            opts.setBundleSupporting(false);
            opts.create().export(ws);

            JsonObject out = JsonResponses.ok("workspace saved");
            out.addProperty("output_path", path.toString());
            res.json(out);
        });

        LOG.log(Level.INFO, "BytecodeEditRoutes.register() completed successfully");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private RouteSupport.MethodTarget resolveMethod(String className, String methodName,
                                                    String descriptor, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = requireWorkspace(res);
        if (ws == null) return null;
        return RouteSupport.requireMethod(ws, className, methodName, descriptor, res);
    }

    private Workspace requireWorkspace(com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) res.status(409).json(JsonResponses.error("no workspace open"));
        return ws;
    }

    private void singleInsnOp(com.zinja.recafmcp.http.Request req,
                              com.zinja.recafmcp.http.Response res, String op) throws Exception {
        RouteSupport.MethodTarget target = resolveMethod(
                req.bodyString("class_name", ""), req.bodyString("method_name", ""),
                req.bodyString("descriptor", ""), res);
        if (target == null) return;

        int index = req.bodyInt("index", -1);
        if (index < 0) {
            res.status(400).json(JsonResponses.error("missing 'index'"));
            return;
        }

        ClassNode cn = BytecodeEditSupport.readClassNode(target.classInfo());
        MethodNode method = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(target.name()) && m.desc.equals(target.descriptor())) {
                method = m;
                break;
            }
        }
        if (method == null) {
            res.status(404).json(JsonResponses.error("method not found in bytecode"));
            return;
        }

        InsnList insns = method.instructions;

        if ("remove".equals(op)) {
            if (index >= insns.size()) {
                res.status(400).json(JsonResponses.error("index out of range"));
                return;
            }
            insns.remove(insns.get(index));
        } else {
            JsonObject newInsn = req.body().getAsJsonObject("instruction");
            if (newInsn == null) {
                res.status(400).json(JsonResponses.error("missing 'instruction'"));
                return;
            }
            String opName = newInsn.get("opcode").getAsString().toUpperCase(Locale.ROOT);
            JsonArray args = newInsn.has("args") ? newInsn.getAsJsonArray("args") : new JsonArray();
            try {
                var insn = BytecodeEditSupport.buildSingleInstruction(opName, args);
                if ("replace".equals(op)) {
                    if (index >= insns.size()) {
                        res.status(400).json(JsonResponses.error("index out of range"));
                        return;
                    }
                    insns.set(insns.get(index), insn);
                } else { // insert
                    if (index > insns.size()) {
                        res.status(400).json(JsonResponses.error("index out of range"));
                        return;
                    }
                    if (index < insns.size()) insns.insertBefore(insns.get(index), insn);
                    else insns.add(insn);
                }
            } catch (IllegalArgumentException e) {
                res.status(400).json(JsonResponses.error(e.getMessage()));
                return;
            }
        }

        BytecodeEditSupport.commitClassNode(wm.getCurrent(), target.owner(), target.classInfo(), cn);
        JsonObject out = JsonResponses.ok("instruction " + op + "d");
        out.addProperty("at_index", index);
        res.json(out);
    }

    private static int flagsFromJson(JsonArray arr) {
        int acc = 0;
        if (arr != null) {
            for (JsonElement el : arr) {
                Integer v = BytecodeEditSupport.ACCESS_FLAGS.get(el.getAsString().toLowerCase(Locale.ROOT));
                if (v != null) acc |= v;
            }
        }
        return acc;
    }

    private static List<String> toList(JsonArray arr) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) out.add(el.getAsString());
        return out;
    }

    private static Object fieldValue(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
        return el.getAsString();
    }
}
