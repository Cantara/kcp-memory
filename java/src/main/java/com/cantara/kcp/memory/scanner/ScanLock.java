package com.cantara.kcp.memory.scanner;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One transcript scan at a time in this JVM (#88), across {@link SessionScanner} and
 * {@link AgentSessionScanner}. In the daemon these are started from the startup
 * thread, the 30-minute scheduler and {@code POST /scan}, and could otherwise walk,
 * parse and write the index in parallel on the same SQLite connection.
 */
final class ScanLock {

    static final ReentrantLock INDEX = new ReentrantLock();

    private ScanLock() {}
}
