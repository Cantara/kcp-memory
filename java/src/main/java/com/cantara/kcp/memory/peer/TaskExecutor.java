package com.cantara.kcp.memory.peer;

import java.io.IOException;

/**
 * Executes a task prompt and returns the result text.
 *
 * <p>Default implementation uses {@code claude -p} via ProcessBuilder.
 * Inject a test implementation to avoid spawning real processes in tests.
 */
@FunctionalInterface
public interface TaskExecutor {

    /**
     * Execute the given prompt and return the output.
     *
     * @param prompt the task prompt
     * @return the execution output
     * @throws IOException if execution fails
     */
    String execute(String prompt) throws IOException;
}
