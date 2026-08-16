package com.zinja.recafmcp;

import com.zinja.recafmcp.http.McpHttpServer;
import com.zinja.recafmcp.routes.*;
import com.zinja.recafmcp.state.UiState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
@PluginInformation(
        id = "recaf-mcp-plugin",
        name = "Recaf MCP Plugin",
        version = "0.1.0",
        author = "zinja",
        description = "Exposes Recaf reverse-engineering operations over HTTP for the Recaf MCP server.",
        dependencies = {},
        softDependencies = {}
)
public class RecafMcpPlugin implements Plugin {
    private static final Logger LOG = Logger.getLogger(RecafMcpPlugin.class.getName());
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8750;

    private final WorkspaceManager workspaceManager;
    private final ResourceImporter resourceImporter;
    private final DecompilerManager decompilerManager;
    private final DecompilerManagerConfig decompilerManagerConfig;
    private final SearchService searchService;
    private final MappingApplierService mappingApplierService;
    private final MappingFormatManager mappingFormatManager;
    private final InheritanceGraphService inheritanceGraphService;

    private McpHttpServer server;
    private UiState uiState;

    @Inject
    public RecafMcpPlugin(WorkspaceManager workspaceManager,
                          ResourceImporter resourceImporter,
                          DecompilerManager decompilerManager,
                          DecompilerManagerConfig decompilerManagerConfig,
                          SearchService searchService,
                          MappingApplierService mappingApplierService,
                          MappingFormatManager mappingFormatManager,
                          InheritanceGraphService inheritanceGraphService) {
        this.workspaceManager = workspaceManager;
        this.resourceImporter = resourceImporter;
        this.decompilerManager = decompilerManager;
        this.decompilerManagerConfig = decompilerManagerConfig;
        this.searchService = searchService;
        this.mappingApplierService = mappingApplierService;
        this.mappingFormatManager = mappingFormatManager;
        this.inheritanceGraphService = inheritanceGraphService;
    }

    @Override
    public void onEnable() {
        String host = System.getProperty("recaf.mcp.host", DEFAULT_HOST);
        int port = Integer.getInteger("recaf.mcp.port", DEFAULT_PORT);

        uiState = new UiState();
        uiState.install(workspaceManager);

        server = new McpHttpServer(host, port);
        server.get("/health", (req, res) -> res.text("ok"));

        new WorkspaceRoutes(workspaceManager, resourceImporter, uiState).register(server);
        new ClassRoutes(workspaceManager).register(server);
        new MethodRoutes(workspaceManager, decompilerManager).register(server);
        new SearchRoutes(workspaceManager, searchService).register(server);
        new XrefsRoutes(workspaceManager, searchService, inheritanceGraphService).register(server);
        new RefactorRoutes(workspaceManager, mappingApplierService, mappingFormatManager, inheritanceGraphService)
                .register(server);
        new DecompileRoutes(workspaceManager, decompilerManager, decompilerManagerConfig).register(server);
        new InheritanceRoutes(workspaceManager, inheritanceGraphService).register(server);
        new FilesRoutes(workspaceManager).register(server);
        new ExportRoutes(workspaceManager).register(server);
        new BytecodeEditRoutes(workspaceManager).register(server);
        server.get("/__plugin_diag__", (req, res) -> res.text("plugin ok, routes registered"));

        try {
            server.start();
            LOG.info("Recaf MCP plugin listening on http://" + host + ":" + port);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to start Recaf MCP HTTP server", e);
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (uiState != null) {
            uiState.uninstall(workspaceManager);
            uiState = null;
        }
    }
}
