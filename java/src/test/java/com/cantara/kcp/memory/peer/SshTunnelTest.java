package com.cantara.kcp.memory.peer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #46: SshTunnel.fromUri must stay backward-compatible with every
 * ssh://user@host[:sshport] form already in production use, while accepting
 * an optional ?port= for the remote kcp-memory HTTP port.
 */
class SshTunnelTest {

    @Test
    void parsesUserAndHostWithDefaultSshAndRemotePort() {
        SshTunnel tunnel = SshTunnel.fromUri("ssh://totto@ironclaw0.example.com");
        assertNotNull(tunnel);
        assertEquals("totto@ironclaw0.example.com", tunnel.getPeerId());
    }

    @Test
    void parsesExplicitSshPort() {
        SshTunnel tunnel = SshTunnel.fromUri("ssh://totto@ironclaw0.example.com:2222");
        assertNotNull(tunnel);
        assertEquals("totto@ironclaw0.example.com", tunnel.getPeerId());
    }

    @Test
    void parsesRemotePortQueryParamWithDefaultSshPort() {
        SshTunnel tunnel = SshTunnel.fromUri("ssh://totto@laptop.local?port=7799");
        assertNotNull(tunnel);
        assertEquals("totto@laptop.local", tunnel.getPeerId());
    }

    @Test
    void parsesBothSshPortAndRemotePortQueryParam() {
        SshTunnel tunnel = SshTunnel.fromUri("ssh://totto@laptop.local:2222?port=7799");
        assertNotNull(tunnel);
        assertEquals("totto@laptop.local", tunnel.getPeerId());
    }

    @Test
    void returnsNullForNonSshUri() {
        assertNull(SshTunnel.fromUri("tcp://host:7735"));
    }

    @Test
    void requiresUserInUri() {
        assertThrows(IllegalArgumentException.class, () -> SshTunnel.fromUri("ssh://host-without-user"));
    }
}
