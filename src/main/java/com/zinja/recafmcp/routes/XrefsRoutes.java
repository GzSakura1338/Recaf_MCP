package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class XrefsRoutes {
    private final WorkspaceManager wm;
    private final SearchService searchService;
    private final InheritanceGraphService graphService;

    public XrefsRoutes(WorkspaceManager wm, SearchService searchService, InheritanceGraphService graphService) {
        this.wm = wm;
        this.searchService = searchService;
        this.graphService = graphService;
    }

    public void register(McpHttpServer server) {
        server.get("/xrefs-to-class", (req, res) -> {
            Workspace ws = RouteSupport.requireWorkspace(wm, res);
            String className = RouteSupport.required(req.query("class_name"), "class_name", res);
            if (ws == null || className == null) return;
            res.json(page(search(ws, new ReferenceQuery(exact(RouteSupport.internal(className)))),
                    "references", req.queryInt("offset", 0), req.queryInt("limit", 0),
                    result -> referenceJson(result.getPath(), className, null, null, "class")));
        });

        server.get("/xrefs-to-method", (req, res) -> {
            Workspace ws = RouteSupport.requireWorkspace(wm, res);
            RouteSupport.MethodTarget target = RouteSupport.requireMethod(ws, req.query("class_name"), req.query("method_name"),
                    req.query("descriptor"), res);
            if (target == null) return;
            res.json(page(search(ws, new ReferenceQuery(exact(target.owner()), exact(target.name()), exact(target.descriptor()))),
                    "references", req.queryInt("offset", 0), req.queryInt("limit", 0),
                    result -> referenceJson(result.getPath(), RouteSupport.external(target.owner()), target.name(), target.descriptor(), "method")));
        });

        server.get("/xrefs-to-field", (req, res) -> {
            Workspace ws = RouteSupport.requireWorkspace(wm, res);
            RouteSupport.FieldTarget target = RouteSupport.requireField(ws, req.query("class_name"), req.query("field_name"),
                    req.query("descriptor"), res);
            if (target == null) return;
            res.json(page(search(ws, new ReferenceQuery(exact(target.owner()), exact(target.name()), exact(target.descriptor()))),
                    "references", req.queryInt("offset", 0), req.queryInt("limit", 0),
                    result -> referenceJson(result.getPath(), RouteSupport.external(target.owner()), target.name(), target.descriptor(), "field")));
        });

        server.get("/callees-of-method", (req, res) -> {
            Workspace ws = RouteSupport.requireWorkspace(wm, res);
            RouteSupport.MethodTarget target = RouteSupport.requireMethod(ws, req.query("class_name"), req.query("method_name"),
                    req.query("descriptor"), res);
            if (target == null) return;
            res.json(page(collectCallees(target), "callees", req.queryInt("offset", 0), req.queryInt("limit", 0), value -> value));
        });

        server.get("/overrides-of-method", (req, res) -> {
            Workspace ws = RouteSupport.requireWorkspace(wm, res);
            RouteSupport.MethodTarget target = RouteSupport.requireMethod(ws, req.query("class_name"), req.query("method_name"),
                    req.query("descriptor"), res);
            if (target == null) return;
            res.json(page(collectOverrides(ws, target), "overrides", req.queryInt("offset", 0), req.queryInt("limit", 0), value -> value));
        });
    }

    private List<Result<?>> search(Workspace ws, ReferenceQuery query) {
        return searchService.search(ws, query).stream().toList();
    }

    private List<JsonObject> collectCallees(RouteSupport.MethodTarget target) {
        ClassNode classNode = new ClassNode();
        new ClassReader(target.classInfo().getBytecode()).accept(classNode, 0);

        List<JsonObject> callees = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor())) continue;
            for (int i = 0; i < method.instructions.size(); i++) {
                addCallee(callees, method.instructions.get(i), i);
            }
            break;
        }
        return callees;
    }

    private List<JsonObject> collectOverrides(Workspace ws, RouteSupport.MethodTarget target) {
        TreeSet<String> owners = new TreeSet<>();
        InheritanceGraph graph = graphService.getCurrentWorkspaceInheritanceGraph();
        if (graph == null) return List.of();

        InheritanceVertex vertex = graph.getVertex(target.owner());
        if (vertex == null) return List.of();

        owners.add(target.owner());
        vertex.getAllParents().stream()
                .filter(parent -> parent.hasMethod(target.name(), target.descriptor()))
                .map(InheritanceVertex::getName)
                .forEach(owners::add);
        vertex.getAllChildren().stream()
                .filter(child -> child.hasMethod(target.name(), target.descriptor()))
                .map(InheritanceVertex::getName)
                .forEach(owners::add);

        List<JsonObject> overrides = new ArrayList<>();
        for (String owner : owners) {
            ClassPathNode node = ws.findJvmClass(owner);
            if (node == null) continue;
            JsonObject out = new JsonObject();
            out.addProperty("class_name", RouteSupport.external(owner));
            out.addProperty("method_name", target.name());
            out.addProperty("descriptor", target.descriptor());
            overrides.add(out);
        }
        return overrides;
    }

    private void addCallee(List<JsonObject> callees, Object insn, int index) {
        if (insn instanceof MethodInsnNode call) {
            JsonObject out = new JsonObject();
            out.addProperty("class_name", RouteSupport.external(call.owner));
            out.addProperty("method_name", call.name);
            out.addProperty("descriptor", call.desc);
            out.addProperty("instruction_index", index);
            callees.add(out);
        } else if (insn instanceof InvokeDynamicInsnNode call) {
            JsonObject out = new JsonObject();
            out.addProperty("method_name", call.name);
            out.addProperty("descriptor", call.desc);
            out.addProperty("instruction_index", index);
            out.addProperty("invoke_dynamic", true);
            callees.add(out);
        }
    }

    private JsonObject referenceJson(PathNode<?> path, String targetClass, String targetName, String targetDescriptor, String kind) {
        JsonObject out = new JsonObject();
        out.addProperty("reference_type", kind);
        out.addProperty("target_class", targetClass);
        if (targetName != null) out.addProperty("target_name", targetName);
        if (targetDescriptor != null) out.addProperty("target_descriptor", targetDescriptor);

        ClassPathNode classPath = path.getPathOfType(ClassInfo.class);
        if (classPath != null) out.addProperty("class_name", RouteSupport.external(classPath.getValue().getName()));

        ClassMemberPathNode memberPath = path.getPathOfType(ClassMember.class);
        if (memberPath != null) {
            out.addProperty("member_name", memberPath.getValue().getName());
            out.addProperty("member_descriptor", memberPath.getValue().getDescriptor());
            out.addProperty("member_kind", memberPath.isMethod() ? "method" : "field");
        }

        JvmInstructionPathNode insnPath = path.getPathOfType(org.objectweb.asm.tree.AbstractInsnNode.class);
        if (insnPath != null) out.addProperty("instruction_index", insnPath.getInstructionIndex());
        return out;
    }

    private static StringPredicate exact(String value) {
        return new StringPredicate("equals", value::equals);
    }

    private static <T> JsonObject page(List<T> items, String key, int offset, int limit, java.util.function.Function<T, Object> serializer) {
        return JsonResponses.paginated(items, key, offset, limit, serializer);
    }
}
