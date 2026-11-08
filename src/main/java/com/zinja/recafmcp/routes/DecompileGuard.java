package com.zinja.recafmcp.routes;

import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.Response;
import software.coley.recaf.services.decompile.DecompileResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

final class DecompileGuard {
    private static final long WAIT_SECONDS = 55;
    private static final long MIN_FREE_HEAP = 128L << 20;
    private static final long MAX_HEAP_DIVISOR = 12;

    private static final Map<String, Long> RUNNING = new ConcurrentHashMap<>();

    private DecompileGuard() {
    }

    /**
     * Runs one decompile and returns its result, or writes the error response and returns null.
     * <p>
     * A decompile cannot be interrupted once the decompiler is working on it, and giving up on the request
     * does not stop it. So a class is only decompiled again after the previous run has really finished, and
     * nothing new is started while the heap is nearly full; both piled work up until Recaf ran out of memory.
     */
    static DecompileResult run(String className, Supplier<CompletableFuture<DecompileResult>> start, Response res)
            throws Exception {
        Long startedAt = RUNNING.get(className);
        if (startedAt != null) {
            long seconds = (System.currentTimeMillis() - startedAt) / 1000;
            res.status(409).json(JsonResponses.error("a decompile of " + className + " is still running ("
                    + seconds + "s so far) and cannot be cancelled; wait for it to finish, or read the bytecode "
                    + "instead (get_method_bytecode / get_bytecode_of_class)"));
            return null;
        }

        long freeHeap = freeHeap();
        long needed = Math.max(MIN_FREE_HEAP, Runtime.getRuntime().maxMemory() / MAX_HEAP_DIVISOR);
        if (freeHeap < needed) {
            System.gc();
            freeHeap = freeHeap();
        }
        if (freeHeap < needed) {
            res.status(503).json(JsonResponses.error("Recaf is low on heap memory (" + (freeHeap >> 20) + " MB free of "
                    + (Runtime.getRuntime().maxMemory() >> 20) + " MB); not starting a decompile. Restart Recaf "
                    + "with a larger -Xmx, or read the bytecode instead (get_method_bytecode / get_bytecode_of_class)"));
            return null;
        }

        if (RUNNING.putIfAbsent(className, System.currentTimeMillis()) != null) {
            res.status(409).json(JsonResponses.error("a decompile of " + className + " is already running"));
            return null;
        }
        CompletableFuture<DecompileResult> future;
        try {
            future = start.get();
        } catch (Throwable t) {
            RUNNING.remove(className);
            throw t;
        }
        future.whenComplete((result, error) -> RUNNING.remove(className));

        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            res.status(504).json(JsonResponses.error("decompiling " + className + " is taking longer than "
                    + WAIT_SECONDS + "s and is still running in Recaf; try again later (it will be refused until it "
                    + "finishes), or read the bytecode instead (get_method_bytecode / get_bytecode_of_class)"));
            return null;
        }
    }

    private static long freeHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }
}
