package com.cantara.kcp.memory.peer;

import java.io.IOException;

/**
 * Executes a task prompt and returns the result text.
 *
 * <p>Default implementation uses {@code claude -p} via ProcessBuilder.
 * Inject a test implementation to avoid spawning real processes in tests.
 */
public interface TaskExecutor {

    /**
     * Execute the given prompt with an optional system prompt and return the output.
     *
     * @param prompt       the task prompt (user message)
     * @param systemPrompt optional system prompt / context preamble, or {@code null}
     * @return the execution output
     * @throws IOException if execution fails
     */
    String execute(String prompt, String systemPrompt) throws IOException;

    /**
     * Execute the given prompt with no system prompt.
     *
     * @param prompt the task prompt
     * @return the execution output
     * @throws IOException if execution fails
     */
    default String execute(String prompt) throws IOException {
        return execute(prompt, null);
    }
}
