package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import com.zinja.recafmcp.state.UiState;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkspaceRoutes {
    private final WorkspaceManager wm;
    private final ResourceImporter importer;
    private final UiState uiState;

    public WorkspaceRoutes(WorkspaceManager wm, ResourceImporter importer, UiState uiState) {
        this.wm = wm;
        this.importer = importer;
        this.uiState = uiState;
    }

    public void register(McpHttpServer server) {
        server.get("/workspace/info", (req, res) -> {
            Workspace ws = wm.getCurrent();
            JsonObject out = new JsonObject();
            out.addProperty("has_workspace", ws != null);
            if (ws == null) {
                res.json(out);
                return;
            }

            out.add("primary", resourceSummary(ws.getPrimaryResource()));
            JsonArray supporting = new JsonArray();
            for (WorkspaceResource resource : ws.getSupportingResources()) {
                supporting.add(resourceSummary(resource));
            }
            out.add("supporting", supporting);
            res.json(out);
        });

        server.get("/workspace/supporting", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) {
                return;
            }

            JsonArray resources = new JsonArray();
            for (WorkspaceResource resource : ws.getSupportingResources()) {
                resources.add(resourceSummary(resource));
            }

            JsonObject out = new JsonObject();
            out.add("resources", resources);
            res.json(out);
        });

        server.post("/workspace/close", (req, res) -> {
            boolean closed = wm.closeCurrent();
            res.json(closed ? JsonResponses.ok("workspace closed") : JsonResponses.error("failed to close workspace"));
        });

        server.post("/workspace/open", (req, res) -> {
            Path path = requirePath(req.bodyString("path", ""), res);
            if (path == null) {
                return;
            }

            WorkspaceResource resource = importer.importResource(path);
            resource.setPropertyValue(InputFilePathProperty.KEY, path);
            if (wm.hasCurrentWorkspace()) {
                wm.closeCurrent();
            }
            wm.setCurrentIgnoringConditions(new BasicWorkspace(resource));

            JsonObject out = JsonResponses.ok("workspace opened");
            out.add("primary", resourceSummary(resource));
            res.json(out);
        });

        server.post("/workspace/add-supporting", (req, res) -> {
            Workspace ws = requireWorkspace(res);
            if (ws == null) {
                return;
            }

            Path path = requirePath(req.bodyString("path", ""), res);
            if (path == null) {
                return;
            }

            WorkspaceResource resource = importer.importResource(path);
            resource.setPropertyValue(InputFilePathProperty.KEY, path);
            ws.addSupportingResource(resource);

            JsonObject out = JsonResponses.ok("supporting resource added");
            out.add("resource", resourceSummary(resource));
            res.json(out);
        });

        server.get("/current-class", (req, res) -> {
            String name = uiState.getCurrentClassName();
            JsonObject out = new JsonObject();
            out.addProperty("class_name", RouteSupport.external(name));
            if (name != null) {
                Workspace ws = wm.getCurrent();
                out.addProperty("found", ws != null && ws.findJvmClass(name) != null);
            }
            res.json(out);
        });

        server.get("/selected-text", (req, res) -> {
            JsonObject out = new JsonObject();
            out.addProperty("text", uiState.getSelectedText());
            res.json(out);
        });
    }

    private Workspace requireWorkspace(com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws == null) {
            res.status(409).json(JsonResponses.error("no workspace open"));
            return null;
        }
        return ws;
    }

    private static Path requirePath(String value, com.zinja.recafmcp.http.Response res) throws Exception {
        if (value == null || value.isBlank()) {
            res.status(400).json(JsonResponses.error("missing 'path'"));
            return null;
        }

        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            res.status(404).json(JsonResponses.error("path not found: " + path));
            return null;
        }
        return path;
    }

    private static JsonObject resourceSummary(WorkspaceResource resource) {
        JsonObject out = new JsonObject();
        if (resource == null) {
            return out;
        }

        Path path = RouteSupport.resourcePath(resource);
        out.addProperty("type", resource.getClass().getSimpleName());
        if (path != null) {
            out.addProperty("path", path.toString());
        }
        out.addProperty("class_count", (int) resource.classBundleStreamRecursive()
                .mapToLong(bundle -> bundle.size())
                .sum());
        out.addProperty("file_count", (int) resource.fileBundleStreamRecursive()
                .mapToLong(bundle -> bundle.size())
                .sum());
        return out;
    }
}
