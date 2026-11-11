# HTTPS with playit and Caddy

How the landing page is reached at its public address when the tunnel is playit's HTTPS tunnel. The owner's choices (2026-11-10): Caddy in front, because playit passes the encrypted connection through rather than decrypting it ("Playit does not terminate SSL", its own guide); and the bare domain as the address.

```
visitor ──HTTPS──▶ playit (public :443) ──TLS, untouched, PROXY v2 header──▶ Caddy (127.0.0.1:8443)
visitor ──HTTP───▶ playit (public :80)  ──plain, PROXY v2 header─────────▶ Caddy (127.0.0.1:8880): certificate checks, else a redirect to https
Caddy ──plain HTTP, X-Forwarded-For set by Caddy──▶ Latchkey (127.0.0.1:25680) for example.org
                                                 ──▶ Mapkeeper (127.0.0.1:8180) for map.example.org
```

## The trust boundary

- **Latchkey** listens on 127.0.0.1 only, and believes `X-Forwarded-For` only from the addresses in `landing.trusted-proxies` (`127.0.0.1`: Caddy). Anyone else's header is ignored.
- **Caddy** learns each visitor's real address from the PROXY protocol header that the playit agent adds, and accepts that header only from 127.0.0.1 (the agent on this machine). It then sets `X-Forwarded-For` to that one address, replacing anything the visitor sent.
- **playit** must have PROXY protocol turned on for this tunnel. Without it every visitor looks like 127.0.0.1, so Latchkey's per-visitor limit acts like the site-wide one, and one person guessing codes could lock everyone out for a few minutes. The page stays safe either way, because the site-wide limit and the uniform answers still hold; only fairness suffers.
- Nothing reaches Latchkey or Caddy from the network directly: both listen on 127.0.0.1.

## Caddyfile

```
{
	# Both listeners take connections from the playit agent on this machine only.
	default_bind 127.0.0.1
	http_port 8880
	https_port 8443
	# The public ports are 80 and 443, so Caddy's own redirect (which would add :8443) is off; the http block does it.
	auto_https disable_redirects
	servers :8443 {
		listener_wrappers {
			proxy_protocol {
				timeout 2s
				allow 127.0.0.1/32
			}
			tls
		}
		timeouts {
			read_header 10s
			read_body 10s
			write 30s
			idle 2m
		}
	}
	servers :8880 {
		listener_wrappers {
			proxy_protocol {
				timeout 2s
				allow 127.0.0.1/32
			}
		}
		timeouts {
			read_header 10s
			read_body 10s
			write 30s
			idle 2m
		}
	}
}

example.org {
	request_body {
		max_size 16KB
	}
	reverse_proxy 127.0.0.1:25680
}

map.example.org {
	request_body {
		max_size 16KB
	}
	reverse_proxy 127.0.0.1:8180
}

http://example.org, http://map.example.org {
	redir https://{host}{uri} permanent
}
```

- **Ports** (owner's playit tunnel, 2026-11-10): HTTPS origin port 8443, HTTP origin port 8880, PROXY protocol v2, local address 127.0.0.1. Caddy's `http_port` and `https_port` match, so its certificate checks are answered on the ports playit forwards to.
- **Certificate:** Caddy gets it from Let's Encrypt, by the HTTP challenge on 8880 or the TLS-ALPN challenge on 8443, both arriving through the tunnel. Once the domain's DNS is on Cloudflare, the DNS challenge with a token limited to that zone also works and needs nothing inbound.
- **Plain HTTP** only ever answers certificate checks or redirects to https. Nothing from Latchkey or the map is served over it.
- **Until Mapkeeper is running**, the map block answers 502 with an empty body: Caddy's default, with no details. Leave that block out until then if you'd rather the address didn't answer at all.
- **Timeouts and the 16 KB body limit** stop slow or oversized requests before they reach Latchkey, which refuses bodies over 4 KB itself anyway.
- `landing.public-url` in Latchkey's config is `https://example.org`, and `page.map-address` is `https://map.example.org`.

## The playit tunnel

HTTPS tunnel, local address 127.0.0.1, HTTPS port 8443, HTTP port 8880, PROXY protocol v2, for the bare domain and `map.`. DNS records for both are DNS only, so the encrypted connection reaches Caddy untouched.

## Not yet tested

None of this has run end to end, and the Caddyfile hasn't been checked with `caddy validate` (Caddy isn't installed yet). The owner set up the playit tunnel on 2026-11-10 with these settings; the header check (each visitor's real address arriving) is the first thing to test once Caddy is in. Latchkey's side, believing `X-Forwarded-For` only from a trusted proxy, has HTTP tests (`forwardedAddressesCountOnlyFromTrustedProxies`, `forwardedAddressesFromUntrustedSendersAreIgnored`).
