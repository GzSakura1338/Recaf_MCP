package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Set;
import java.util.TreeSet;

public class InheritanceRoutes {
    private final WorkspaceManager wm;
    private final InheritanceGraphService graphService;

    public InheritanceRoutes(WorkspaceManager wm, InheritanceGraphService graphService) {
        this.wm = wm;
        this.graphService = graphService;
    }

    public void register(McpHttpServer server) {
        server.get("/inheritance/superclasses", (req, res) -> {
            InheritanceVertex v = vertex(req.query("class_name"), res);
            if (v == null) return;
            JsonArray arr = new JsonArray();
            for (InheritanceVertex parent : v.getAllParents()) {
                arr.add(parent.getName().replace('/', '.'));
            }
            JsonObject out = new JsonObject();
            out.addProperty("class_name", v.getName().replace('/', '.'));
            out.add("superclasses", arr);
            res.json(out);
        });

        server.get("/inheritance/interfaces", (req, res) -> {
            // Interfaces are mixed into the parent set by InheritanceVertex.
            // Filter by reading the underlying ClassInfo access flag.
            InheritanceVertex v = vertex(req.query("class_name"), res);
            if (v == null) return;
            JsonArray arr = new JsonArray();
            for (InheritanceVertex p : v.getAllParents()) {
                if (p.getValue() != null && (p.getValue().getAccess() & java.lang.reflect.Modifier.INTERFACE) != 0) {
                    arr.add(p.getName().replace('/', '.'));
                }
            }
            JsonObject out = new JsonObject();
            out.add("interfaces", arr);
            res.json(out);
        });

        server.get("/inheritance/direct-subclasses", (req, res) -> {
            InheritanceVertex v = vertex(req.query("class_name"), res);
            if (v == null) return;
            Set<String> names = new TreeSet<>();
            for (InheritanceVertex c : v.getChildren()) names.add(c.getName().replace('/', '.'));
            respondNames(res, names, req.queryInt("offset", 0), req.queryInt("limit", 0));
        });

        server.get("/inheritance/all-subclasses", (req, res) -> {
            InheritanceVertex v = vertex(req.query("class_name"), res);
            if (v == null) return;
            Set<String> names = new TreeSet<>();
            for (InheritanceVertex c : v.getAllChildren()) names.add(c.getName().replace('/', '.'));
            respondNames(res, names, req.queryInt("offset", 0), req.queryInt("limit", 0));
        });

        server.get("/inheritance/implementors", (req, res) -> {
            InheritanceVertex v = vertex(req.query("interface_name"), res);
            if (v == null) return;
            Set<String> names = new TreeSet<>();
            for (InheritanceVertex c : v.getAllChildren()) names.add(c.getName().replace('/', '.'));
            respondNames(res, names, req.queryInt("offset", 0), req.queryInt("limit", 0));
        });
    }

    private InheritanceVertex vertex(String param, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) {
            res.status(409).json(JsonResponses.error("no workspace open"));
            return null;
        }
        if (param == null || param.isEmpty()) {
            res.status(400).json(JsonResponses.error("missing class/interface name"));
            return null;
        }
        InheritanceGraph graph = graphService.getCurrentWorkspaceInheritanceGraph();
        if (graph == null) {
            res.status(409).json(JsonResponses.error("inheritance graph unavailable"));
            return null;
        }
        InheritanceVertex v = graph.getVertex(param.replace('.', '/'));
        if (v == null) {
            res.status(404).json(JsonResponses.error("class not found in graph: " + param));
            return null;
        }
        return v;
    }

    private static void respondNames(com.zinja.recafmcp.http.Response res, Set<String> names, int offset, int limit) throws Exception {
        JsonObject out = JsonResponses.paginated(
                new java.util.ArrayList<>(names), "classes", offset, limit,
                n -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", (String) n);
                    return o;
                });
        res.json(out);
    }
}
