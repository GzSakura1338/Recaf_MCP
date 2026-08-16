package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

public class ClassRoutes {
    private final WorkspaceManager wm;

    public ClassRoutes(WorkspaceManager wm) {
        this.wm = wm;
    }

    public void register(McpHttpServer server) {
        server.get("/all-classes", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            int offset = req.queryInt("offset", 0);
            int limit = req.queryInt("limit", 0);

            List<String> names = ws.jvmClassesStream()
                    .map(ClassPathNode::getValue)
                    .map(ClassInfo::getName)
                    .map(n -> n.replace('/', '.'))
                    .sorted()
                    .collect(Collectors.toList());

            JsonObject out = JsonResponses.paginated(names, "classes", offset, limit,
                    n -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", (String) n);
                        return o;
                    });
            res.json(out);
        });

        server.get("/class-info", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            res.json(classInfoSummary(cls));
        });

        server.get("/class-source", (req, res) -> {
            // Decompilation lives in DecompileRoutes; this endpoint is a shortcut
            // that delegates with the workspace default decompiler.
            res.status(308).json(JsonResponses.error("use /decompile?class_name=... instead"));
        });

        server.get("/bytecode-of-class", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            StringWriter sw = new StringWriter();
            TraceClassVisitor visitor = new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw));
            cls.getClassReader().accept(visitor, 0);
            JsonObject out = new JsonObject();
            out.addProperty("class_name", cls.getName().replace('/', '.'));
            out.addProperty("bytecode", sw.toString());
            res.json(out);
        });

        server.get("/methods-of-class", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            JsonArray arr = new JsonArray();
            for (MethodMember m : cls.getMethods()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", m.getName());
                o.addProperty("descriptor", m.getDescriptor());
                o.addProperty("access", m.getAccess());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.addProperty("class_name", cls.getName().replace('/', '.'));
            out.add("methods", arr);
            res.json(out);
        });

        server.get("/fields-of-class", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            JsonArray arr = new JsonArray();
            for (FieldMember f : cls.getFields()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", f.getName());
                o.addProperty("descriptor", f.getDescriptor());
                o.addProperty("access", f.getAccess());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.addProperty("class_name", cls.getName().replace('/', '.'));
            out.add("fields", arr);
            res.json(out);
        });

        server.get("/inner-classes", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            JsonArray arr = new JsonArray();
            for (InnerClassInfo ic : cls.getInnerClasses()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", ic.getInnerClassName().replace('/', '.'));
                if (ic.getOuterClassName() != null) o.addProperty("outer", ic.getOuterClassName().replace('/', '.'));
                if (ic.getInnerName() != null) o.addProperty("simple_name", ic.getInnerName());
                o.addProperty("access", ic.getInnerAccess());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.add("inner_classes", arr);
            res.json(out);
        });

        server.get("/annotations-of-class", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            JsonArray arr = new JsonArray();
            for (AnnotationInfo a : cls.getAnnotations()) {
                JsonObject o = new JsonObject();
                o.addProperty("descriptor", a.getDescriptor());
                o.addProperty("visible", a.isVisible());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.add("annotations", arr);
            res.json(out);
        });

        server.get("/class-bytes", (req, res) -> {
            JvmClassInfo cls = requireClass(req.query("class_name"), res);
            if (cls == null) return;
            JsonObject out = new JsonObject();
            out.addProperty("class_name", cls.getName().replace('/', '.'));
            out.addProperty("bytes_base64", Base64.getEncoder().encodeToString(cls.getBytecode()));
            out.addProperty("length", cls.getBytecode().length);
            res.json(out);
        });
    }

    private JvmClassInfo requireClass(String nameParam, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) {
            res.status(409).json(JsonResponses.error("no workspace open"));
            return null;
        }
        if (nameParam == null || nameParam.isEmpty()) {
            res.status(400).json(JsonResponses.error("missing 'class_name'"));
            return null;
        }
        String internal = nameParam.replace('.', '/');
        ClassPathNode node = ws.findJvmClass(internal);
        if (node == null) {
            res.status(404).json(JsonResponses.error("class not found: " + nameParam));
            return null;
        }
        return (JvmClassInfo) node.getValue();
    }

    private static JsonObject classInfoSummary(JvmClassInfo cls) {
        JsonObject o = new JsonObject();
        o.addProperty("name", cls.getName().replace('/', '.'));
        o.addProperty("super", cls.getSuperName() == null ? null : cls.getSuperName().replace('/', '.'));
        JsonArray interfaces = new JsonArray();
        for (String iface : cls.getInterfaces()) interfaces.add(iface.replace('/', '.'));
        o.add("interfaces", interfaces);
        o.addProperty("access", cls.getAccess());
        o.addProperty("version", cls.getVersion());
        o.addProperty("method_count", cls.getMethods().size());
        o.addProperty("field_count", cls.getFields().size());
        o.addProperty("inner_count", cls.getInnerClasses().size());
        o.addProperty("source_file", cls.getSourceFileName());
        return o;
    }
}
