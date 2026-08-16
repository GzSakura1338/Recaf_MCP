package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MethodRoutes {
    private final WorkspaceManager wm;
    private final DecompilerManager dm;

    public MethodRoutes(WorkspaceManager wm, DecompilerManager dm) {
        this.wm = wm;
        this.dm = dm;
    }

    public void register(McpHttpServer server) {
        server.get("/method-by-name", (req, res) -> {
            Lookup lookup = resolve(req.query("class_name"), req.query("method_name"), req.query("descriptor"), res);
            if (lookup == null) return;

            DecompileResult result = dm.decompile(wm.getCurrent(), lookup.cls).get(60, TimeUnit.SECONDS);
            if (result.getException() != null) {
                res.status(500).json(JsonResponses.error(result.getException().toString()));
                return;
            }

            MethodSourceSupport.ExtractedMethod extracted = MethodSourceSupport.extract(
                    result.getText(), lookup.cls.getName(), lookup.method.getName());

            JsonObject out = new JsonObject();
            out.addProperty("class_name", lookup.cls.getName().replace('/', '.'));
            out.addProperty("method_name", lookup.method.getName());
            out.addProperty("descriptor", lookup.method.getDescriptor());
            out.addProperty("decompiler", dm.getTargetJvmDecompiler().getName());
            if (extracted == null) {
                out.addProperty("warning", "method source could not be isolated; returning full class text");
                out.addProperty("source", result.getText());
                out.addProperty("text", result.getText());
            } else {
                out.addProperty("source", extracted.source());
                out.addProperty("text", extracted.source());
                out.addProperty("line_start", extracted.lineStart());
                out.addProperty("line_end", extracted.lineEnd());
            }
            res.json(out);
        });

        server.get("/method-bytecode", (req, res) -> {
            Lookup lookup = resolve(req.query("class_name"), req.query("method_name"), req.query("descriptor"), res);
            if (lookup == null) return;

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            Textifier textifier = new Textifier();
            boolean[] emitted = {false};

            new ClassReader(lookup.cls.getBytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    boolean nameMatch = name.equals(lookup.method.getName());
                    boolean descMatch = lookup.descriptorFilter == null || lookup.descriptorFilter.equals(descriptor);
                    if (nameMatch && descMatch && !emitted[0]) {
                        emitted[0] = true;
                        return new TraceMethodVisitor(null, textifier);
                    }
                    return null;
                }
            }, 0);

            textifier.print(pw);
            JsonObject out = new JsonObject();
            out.addProperty("class_name", lookup.cls.getName().replace('/', '.'));
            out.addProperty("method_name", lookup.method.getName());
            out.addProperty("descriptor", lookup.method.getDescriptor());
            out.addProperty("bytecode", sw.toString());
            res.json(out);
        });

        server.get("/method-info", (req, res) -> {
            Lookup lookup = resolve(req.query("class_name"), req.query("method_name"), req.query("descriptor"), res);
            if (lookup == null) return;

            MethodMember method = lookup.method;
            JsonObject out = new JsonObject();
            out.addProperty("class_name", lookup.cls.getName().replace('/', '.'));
            out.addProperty("name", method.getName());
            out.addProperty("descriptor", method.getDescriptor());
            out.addProperty("access", method.getAccess());
            out.addProperty("signature", method.getSignature());

            JsonArray exceptions = new JsonArray();
            if (method.getThrownTypes() != null) {
                for (String type : method.getThrownTypes()) exceptions.add(type.replace('/', '.'));
            }
            out.add("exceptions", exceptions);
            res.json(out);
        });
    }

    private Lookup resolve(String className, String methodName, String descriptor, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) {
            res.status(409).json(JsonResponses.error("no workspace open"));
            return null;
        }
        if (className == null || methodName == null || className.isEmpty() || methodName.isEmpty()) {
            res.status(400).json(JsonResponses.error("missing 'class_name' or 'method_name'"));
            return null;
        }

        ClassPathNode node = ws.findJvmClass(className.replace('.', '/'));
        if (node == null) {
            res.status(404).json(JsonResponses.error("class not found"));
            return null;
        }

        JvmClassInfo cls = (JvmClassInfo) node.getValue();
        List<MethodMember> candidates = cls.getMethods().stream()
                .filter(method -> method.getName().equals(methodName))
                .filter(method -> descriptor == null || descriptor.isEmpty() || method.getDescriptor().equals(descriptor))
                .toList();

        if (candidates.isEmpty()) {
            res.status(404).json(JsonResponses.error("method not found"));
            return null;
        }
        if (candidates.size() > 1) {
            JsonArray options = new JsonArray();
            for (MethodMember method : candidates) {
                JsonObject option = new JsonObject();
                option.addProperty("descriptor", method.getDescriptor());
                options.add(option);
            }
            JsonObject err = JsonResponses.error("method name is ambiguous; pass 'descriptor' to disambiguate");
            err.add("candidates", options);
            res.status(409).json(err);
            return null;
        }

        return new Lookup(cls, candidates.get(0), descriptor != null && !descriptor.isEmpty() ? descriptor : null);
    }

    private record Lookup(JvmClassInfo cls, MethodMember method, String descriptorFilter) {
    }
}
