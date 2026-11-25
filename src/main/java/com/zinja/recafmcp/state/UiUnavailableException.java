package com.zinja.recafmcp.state;

/**
 * Thrown when a route needs the Recaf JavaFX UI, but Recaf was started without it
 * ({@code recaf --headless ...}).
 * <p>
 * Headless Recaf has no windows, no editor tabs and no toolkit, so "current class"
 * and "selected text" have no meaning there. Callers turn this into a normal JSON
 * payload instead of a 500, so an MCP client learns why the tool is empty.
 */
public class UiUnavailableException extends RuntimeException {
    public UiUnavailableException(String message) {
        super(message);
    }
}
