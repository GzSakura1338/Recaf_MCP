package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.McpHttpServer;
import com.zinja.recafmcp.state.UiState;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.EmptyWorkspace;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.file.Path;
import java.util.UUID;

public class InstanceRoutes {
    private final WorkspaceManager wm;
    private final McpHttpServer server;
    private final UiState uiState;
    private final String instanceId = UUID.randomUUID().toString();

    public InstanceRoutes(WorkspaceManager wm, McpHttpServer server, UiState uiState) {
        this.wm = wm;
        this.server = server;
        this.uiState = uiState;
    }

    public void register(McpHttpServer server) {
        server.get("/instance", (req, res) -> {
            JsonObject out = new JsonObject();
            out.addProperty("instance_id", instanceId);
            out.addProperty("pid", ProcessHandle.current().pid());
            out.addProperty("port", this.server.getPort());
            // Headless Recaf (recaf --headless --idle) has no UI, which MCP clients
            // need to know: UI tools come back empty there.
            out.addProperty("headless", uiState.isHeadless());
            out.addProperty("ui_available", uiState.isAvailable());

            Workspace ws = wm.getCurrent();
            boolean open = ws != null && !(ws instanceof EmptyWorkspace);
            out.addProperty("has_workspace", open);
            if (open) {
                out.addProperty("jar_name", RouteSupport.resourceName(ws.getPrimaryResource()));
                Path path = RouteSupport.resourcePath(ws.getPrimaryResource());
                out.addProperty("jar_path", path == null ? null : path.toString());
            }
            res.json(out);
        });
    }
}
