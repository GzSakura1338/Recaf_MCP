package com.zinja.recafmcp.state;

import jakarta.enterprise.inject.Instance;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.recaf.launch.LaunchArguments;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.ui.docking.DockingManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public class UiState {
    private static final long FX_TIMEOUT_SECONDS = 5;

    public static final String HEADLESS_REASON =
            "Recaf is running headless (no JavaFX UI); this tool needs the GUI. "
                    + "Use get_class_source, get_all_classes, get_method_bytecode, ... instead.";

    private final Instance<DockingManager> dockingManager;
    private final Instance<LaunchArguments> launchArguments;

    /**
     * {@code null} until the toolkit answers; {@code true} once it does. A failed
     * probe is never cached because a GUI Recaf may finish starting its toolkit
     * after the plugin is enabled.
     */
    private volatile Boolean toolkitReady;

    public UiState(Instance<DockingManager> dockingManager, Instance<LaunchArguments> launchArguments) {
        this.dockingManager = dockingManager;
        this.launchArguments = launchArguments;
    }

    /**
     * @return {@code true} when Recaf was started with {@code --headless}.
     * Prefers Recaf's own parsed launch arguments; falls back to probing JavaFX.
     */
    public boolean isHeadless() {
        try {
            if (launchArguments != null && launchArguments.isResolvable()) {
                return launchArguments.get().isHeadless();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to the toolkit probe below.
        }
        return !isAvailable();
    }

    /**
     * @return {@code true} when the JavaFX toolkit is initialized, so UI lookups can work.
     */
    public boolean isAvailable() {
        Boolean ready = toolkitReady;
        if (ready != null) {
            return ready;
        }
        try {
            Platform.runLater(() -> {
            });
        } catch (IllegalStateException | LinkageError e) {
            return false;
        }
        toolkitReady = Boolean.TRUE;
        return true;
    }

    public String getCurrentClassName() throws Exception {
        requireUi();
        return onFxThread(() -> {
            Node pane = currentClassPane();
            return pane == null ? null : ((ClassNavigable) pane).getClassPath().getValue().getName();
        });
    }

    public String getSelectedText() throws Exception {
        requireUi();
        return onFxThread(() -> {
            String selection = selectionOf((CodeArea) focusedAncestor(CodeArea.class::isInstance));
            if (selection != null) return selection;

            Node pane = currentClassPane();
            if (pane == null) return null;
            List<CodeArea> areas = new ArrayList<>();
            collectCodeAreas(pane, areas);
            for (CodeArea area : areas) {
                selection = selectionOf(area);
                if (selection != null) return selection;
            }
            return null;
        });
    }

    private void requireUi() {
        if (!isAvailable()) {
            throw new UiUnavailableException(HEADLESS_REASON);
        }
    }

    private Node currentClassPane() {
        Node focused = focusedAncestor(ClassNavigable.class::isInstance);
        if (focused != null) return focused;

        if (!dockingManager.isResolvable()) return null;
        DockContainerLeaf primary;
        try {
            primary = dockingManager.get().getPrimaryDockingContainer();
        } catch (IllegalStateException e) {
            // GUI mode, but the main window is still being built: the plugin's HTTP
            // server starts before the UI is ready. No selection exists yet.
            return null;
        }
        Dockable selected = primary == null ? null : primary.getSelectedDockable();
        return selected != null && selected.getNode() instanceof ClassNavigable ? selected.getNode() : null;
    }

    private static Node focusedAncestor(Predicate<Node> match) {
        List<Window> windows = new ArrayList<>(Window.getWindows());
        windows.removeIf(window -> !window.isShowing());
        windows.sort(Comparator.comparing(window -> !window.isFocused()));
        for (Window window : windows) {
            Scene scene = window.getScene();
            for (Node node = scene == null ? null : scene.getFocusOwner(); node != null; node = node.getParent()) {
                if (match.test(node)) return node;
            }
        }
        return null;
    }

    private static void collectCodeAreas(Node node, List<CodeArea> out) {
        if (node instanceof CodeArea area) {
            out.add(area);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectCodeAreas(child, out);
        }
    }

    private static String selectionOf(CodeArea area) {
        if (area == null) return null;
        String text = area.getSelectedText();
        return text == null || text.isEmpty() ? null : text;
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();

        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Recaf UI thread did not respond within " + FX_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
