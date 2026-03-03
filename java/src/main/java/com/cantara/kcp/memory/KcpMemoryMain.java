package com.cantara.kcp.memory;

import com.cantara.kcp.memory.cli.KcpMemoryCli;
import picocli.CommandLine;

/**
 * Entry point — delegates to the Picocli command tree.
 */
public class KcpMemoryMain {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KcpMemoryCli()).execute(args);
        System.exit(exitCode);
    }
}
