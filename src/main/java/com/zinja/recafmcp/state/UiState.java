package com.zinja.recafmcp.state;

import software.coley.recaf.services.workspace.WorkspaceCloseListener;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Tracks UI-adjacent state that the HTTP endpoints expose: the currently
 * focused class (active editor tab) and the user's current text selection.
 * <p>
 * For an initial scaffold this only listens for workspace open/close — the UI
 * layer (ContentPane / DockingTab) hasn't been wired in yet. Filling in the
 * JavaFX side is TODO:
 * <p>
 * 1. Listen to Recaf's docking system for tab focus changes and push the
 * active {@code ClassPathNode} into {@link #setCurrentClassName(String)}.
 * 2. Listen to the active editor's {@code CodeArea.caretSelectionBind}
 * and push into {@link #setSelectedText(String)}.
 * <p>
 * The docking API lives in {@code software.coley.recaf.ui.docking.DockingManager};
 * the editor hook lives on {@code AbstractContentPane}.
 */
public class UiState {
    private final WorkspaceOpenListener openListener = this::onWorkspaceOpen;
    private final WorkspaceCloseListener closeListener = this::onWorkspaceClose;

    private volatile String currentClassName;
    private volatile String selectedText;

    public void install(WorkspaceManager manager) {
        manager.addWorkspaceOpenListener(openListener);
        manager.addWorkspaceCloseListener(closeListener);
    }

    public void uninstall(WorkspaceManager manager) {
        manager.removeWorkspaceOpenListener(openListener);
        manager.removeWorkspaceCloseListener(closeListener);
    }

    public String getCurrentClassName() {
        return currentClassName;
    }

    public void setCurrentClassName(String currentClassName) {
        this.currentClassName = currentClassName;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    private void onWorkspaceOpen(Workspace workspace) {
        // Nothing to seed yet; the docking listener will push the first class.
    }

    private void onWorkspaceClose(Workspace workspace) {
        currentClassName = null;
        selectedText = null;
    }
}
