package com.zinja.recafmcp;

import com.zinja.recafmcp.http.McpHttpServer;
import com.zinja.recafmcp.routes.*;
import com.zinja.recafmcp.state.UiState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.launch.LaunchArguments;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.ui.docking.DockingManager;

import java.util.Set;
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
    private static final int PORT_SPAN = 20;
    private static final Set<Integer> RESERVED_PORTS = Set.of(8751);

    private final WorkspaceManager workspaceManager;
    private final ResourceImporter resourceImporter;
    private final DecompilerManager decompilerManager;
    private final DecompilerManagerConfig decompilerManagerConfig;
    private final SearchService searchService;
    private final MappingApplierService mappingApplierService;
    private final MappingFormatManager mappingFormatManager;
    private final InheritanceGraphService inheritanceGraphService;
    private final Instance<DockingManager> dockingManager;
    private final Instance<LaunchArguments> launchArguments;

    private McpHttpServer server;

    @Inject
    public RecafMcpPlugin(WorkspaceManager workspaceManager,
                          ResourceImporter resourceImporter,
                          DecompilerManager decompilerManager,
                          DecompilerManagerConfig decompilerManagerConfig,
                          SearchService searchService,
                          MappingApplierService mappingApplierService,
                          MappingFormatManager mappingFormatManager,
                          InheritanceGraphService inheritanceGraphService,
                          Instance<DockingManager> dockingManager,
                          Instance<LaunchArguments> launchArguments) {
        this.workspaceManager = workspaceManager;
        this.resourceImporter = resourceImporter;
        this.decompilerManager = decompilerManager;
        this.decompilerManagerConfig = decompilerManagerConfig;
        this.searchService = searchService;
        this.mappingApplierService = mappingApplierService;
        this.mappingFormatManager = mappingFormatManager;
        this.inheritanceGraphService = inheritanceGraphService;
        this.dockingManager = dockingManager;
        this.launchArguments = launchArguments;
    }

    @Override
    public void onEnable() {
        String host = setting("recaf.mcp.host", "RECAF_MCP_HOST", DEFAULT_HOST);
        int port = intSetting("recaf.mcp.port", "RECAF_MCP_PORT", DEFAULT_PORT);
        UiState uiState = new UiState(dockingManager, launchArguments);

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
        new InstanceRoutes(workspaceManager, server, uiState).register(server);
        server.get("/__plugin_diag__", (req, res) -> res.text("plugin ok, routes registered"));

        try {
            server.start(PORT_SPAN, RESERVED_PORTS);
            LOG.info("Recaf MCP plugin listening on http://" + host + ":" + server.getPort());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to start Recaf MCP HTTP server", e);
        }
    }

    /**
     * Resolve a setting from a system property first, then an environment variable.
     * Environment support keeps {@code recaf --headless} usable from systemd units,
     * containers and the Linux launcher scripts, where {@code -D} flags are awkward.
     */
    private static String setting(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intSetting(String property, String env, int fallback) {
        String value = setting(property, env, null);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warning("Ignoring invalid " + property + "/" + env + " value '" + value + "'");
            return fallback;
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
