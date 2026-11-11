package com.extantape.latchkey.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;

/**
 * The playit.gg preset (arrival-flow brief, item 7): what to set up so the landing page is reachable through a playit
 * HTTPS tunnel, and whether the pieces on this machine are there.
 *
 * <p>playit passes the encrypted connection through without decrypting it, so a proxy on this machine must hold the
 * certificate. The preset uses Caddy, the owner's choice (2026-11-10): TLS on 127.0.0.1:{@value #HTTPS_PORT}, plain HTTP on
 * 127.0.0.1:{@value #HTTP_PORT} for certificate checks and the redirect to https, both behind PROXY protocol v2 from
 * the playit agent on this machine.
 */
public final class TunnelPreset {
    public static final int HTTPS_PORT = 8443;
    public static final int HTTP_PORT = 8880;
    /** Mapkeeper's default port, for the map's own address when one is set. */
    public static final int MAP_PORT = 8180;

    /** What's on this machine, as last seen. Null means not checked or couldn't tell. */
    public record Seen(long checkedAt, Boolean agentRunning, Boolean proxyListening) {}

    private TunnelPreset() {}

    /** The playit tunnel to create, as the lines to show. */
    public static List<String> playitSteps(URI publicUrl, String mapUrl) {
        String host = publicUrl.getHost();
        String names = mapUrl.isEmpty() ? host : host + " and " + URI.create(mapUrl).getHost();
        return List.of(
                "In playit.gg: Tunnels, then Add Tunnel. Type: HTTPS.",
                "Local IP 127.0.0.1, HTTPS port " + HTTPS_PORT + ", HTTP port " + HTTP_PORT + ", Proxy Protocol V2.",
                "Domain: " + names + ". In your DNS, point each at what playit gives you, DNS only (not proxied), so the certificate stays on this machine.",
                "Then Caddy: /latchkey tunnel caddyfile writes plugins/Latchkey/Caddyfile for it.");
    }

    /** A Caddyfile for this server: Latchkey at its bind and port, and Mapkeeper for the map's address if one is set. */
    public static String caddyfile(URI publicUrl, String bind, int port, String mapUrl) {
        String host = publicUrl.getHost();
        String mapHost = mapUrl.isEmpty() ? null : URI.create(mapUrl).getHost();
        String listener = """
                	servers :%d {
                		listener_wrappers {
                			proxy_protocol {
                				timeout 2s
                				allow 127.0.0.1/32
                			}
                %s		}
                		timeouts {
                			read_header 10s
                			read_body 10s
                			write 30s
                			idle 2m
                		}
                	}
                """;
        StringBuilder b = new StringBuilder();
        b.append("# Written by Latchkey's playit preset. Check it, then run: caddy validate --config Caddyfile\n")
         .append("# Only the playit agent on this machine may connect (PROXY protocol from 127.0.0.1).\n")
         .append("{\n")
         .append("\tdefault_bind 127.0.0.1\n")
         .append("\thttp_port ").append(HTTP_PORT).append('\n')
         .append("\thttps_port ").append(HTTPS_PORT).append('\n')
         .append("\t# The public ports are 80 and 443, so Caddy's own redirect (which would add the port) is off.\n")
         .append("\tauto_https disable_redirects\n")
         .append(listener.formatted(HTTPS_PORT, "\t\t\ttls\n"))
         .append(listener.formatted(HTTP_PORT, ""))
         .append("}\n\n")
         .append(host).append(" {\n\trequest_body {\n\t\tmax_size 16KB\n\t}\n\treverse_proxy ").append(hostPort(bind, port)).append("\n}\n");
        if (mapHost != null) {
            b.append('\n').append(mapHost).append(" {\n\trequest_body {\n\t\tmax_size 16KB\n\t}\n\treverse_proxy 127.0.0.1:").append(MAP_PORT).append("\n}\n");
        }
        b.append("\nhttp://").append(host);
        if (mapHost != null) b.append(", http://").append(mapHost);
        b.append(" {\n\tredir https://{host}{uri} permanent\n}\n");
        return b.toString();
    }

    private static String hostPort(String bind, int port) {
        return (bind.contains(":") ? "[" + bind + "]" : bind) + ":" + port;
    }

    /** Whether a playit agent seems to run on this machine. Null if the process list can't be read. */
    public static Boolean agentRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> p.info().command()
                    .map(c -> { String n = c.substring(c.lastIndexOf('/') + 1).toLowerCase(); return n.startsWith("playit"); }).orElse(false));
        } catch (SecurityException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** Whether something accepts connections on 127.0.0.1 at the proxy's TLS port. */
    public static boolean proxyListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", HTTPS_PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
