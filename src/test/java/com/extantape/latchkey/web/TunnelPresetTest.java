package com.extantape.latchkey.web;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class TunnelPresetTest {
    static final URI PLAY = URI.create("https://play.example.org");

    @Test void theCaddyfileListensOnThisMachineOnlyBehindProxyProtocol() {
        String c = TunnelPreset.caddyfile(PLAY, "127.0.0.1", 25680, "");
        assertTrue(c.contains("default_bind 127.0.0.1"));
        assertTrue(c.contains("https_port 8443") && c.contains("http_port 8880"));
        assertEquals(2, c.split("proxy_protocol \\{", -1).length - 1, "both listeners take PROXY protocol");
        assertEquals(2, c.split("allow 127.0.0.1/32", -1).length - 1, "and only from the agent on this machine");
        assertTrue(c.contains("auto_https disable_redirects"));
        assertTrue(c.contains("play.example.org {\n\trequest_body {\n\t\tmax_size 16KB\n\t}\n\treverse_proxy 127.0.0.1:25680"));
        assertTrue(c.contains("http://play.example.org {\n\tredir https://{host}{uri} permanent"));
        assertFalse(c.contains("8180"), "no map block without a map address");
    }

    @Test void aMapAddressGetsItsOwnBlockToMapkeeper() {
        String c = TunnelPreset.caddyfile(PLAY, "127.0.0.1", 25680, "https://map.example.org");
        assertTrue(c.contains("map.example.org {\n\trequest_body {\n\t\tmax_size 16KB\n\t}\n\treverse_proxy 127.0.0.1:8180"));
        assertTrue(c.contains("http://play.example.org, http://map.example.org {"));
    }

    @Test void anIpv6BindIsBracketed() {
        assertTrue(TunnelPreset.caddyfile(PLAY, "::1", 25680, "").contains("reverse_proxy [::1]:25680"));
    }

    @Test void theStepsNameTheTunnelSettingsAndBothNames() {
        String s = String.join(" ", TunnelPreset.playitSteps(PLAY, "https://map.example.org"));
        assertTrue(s.contains("Type: HTTPS"));
        assertTrue(s.contains("Local IP 127.0.0.1, HTTPS port 8443, HTTP port 8880, Proxy Protocol V2"));
        assertTrue(s.contains("play.example.org and map.example.org"));
        assertTrue(s.contains("DNS only"));
    }

    @Test void theProxyCheckSeesSomethingListening() throws Exception {
        // Only meaningful if 8443 is free here; if something already listens, the check must say so.
        try (var s = new java.net.ServerSocket()) {
            try {
                s.bind(new java.net.InetSocketAddress("127.0.0.1", TunnelPreset.HTTPS_PORT));
            } catch (java.io.IOException taken) {
                assertTrue(TunnelPreset.proxyListening());
                return;
            }
            assertTrue(TunnelPreset.proxyListening());
        }
    }

}
