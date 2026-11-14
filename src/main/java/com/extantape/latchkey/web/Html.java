package com.extantape.latchkey.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The landing page's HTML. Plain forms that work without JavaScript, with labels, visible focus and WCAG 2.2 AA
 * contrast in light and dark. The one script only moves a sign-in token from the address bar into the form; the CSP
 * allows exactly that script and that stylesheet, by hash.
 */
public final class Html {
    static final String CSS = """
            :root{color-scheme:light dark;--fg:#1a1a1a;--bg:#fff;--muted:#4a4a4a;--line:#767676;--btn:#0b5394;--btnfg:#fff;--bad:#a4161a;--ok:#1b5e20}
            @media (prefers-color-scheme:dark){:root{--fg:#f2f2f2;--bg:#141414;--muted:#c4c4c4;--line:#8a8a8a;--btn:#9fc5f8;--btnfg:#0a0a0a;--bad:#ff9a9a;--ok:#9be29f}}
            body{font:1.125rem/1.5 system-ui,sans-serif;color:var(--fg);background:var(--bg);margin:0;padding:2rem 1rem}
            main{max-width:28rem;margin:0 auto}
            h1{font-size:1.75rem;margin:0 0 1rem}
            p{margin:0 0 1rem}
            .hint{color:var(--muted);font-size:1rem}
            label{display:block;font-weight:600;margin:1rem 0 .25rem}
            input{font:inherit;width:100%;box-sizing:border-box;padding:.6rem .7rem;border:2px solid var(--line);border-radius:.4rem;background:var(--bg);color:var(--fg)}
            button{font:inherit;font-weight:600;margin-top:1.25rem;padding:.65rem 1.25rem;border:0;border-radius:.4rem;background:var(--btn);color:var(--btnfg);cursor:pointer}
            input:focus-visible,button:focus-visible{outline:3px solid var(--fg);outline-offset:2px}
            .bad{color:var(--bad);font-weight:600}
            .ok{color:var(--ok);font-weight:600}
            hr{border:0;border-top:1px solid var(--line);margin:2rem 0}
            a{color:var(--btn)}
            .logo{display:block;max-width:12rem;max-height:8rem;margin:0 auto 1.5rem}
            .addr{display:flex;flex-wrap:wrap;align-items:center;gap:.75rem;margin:0 0 1.5rem}
            .addr code{font:600 1.5rem/1.3 ui-monospace,monospace;padding:.5rem .75rem;border:2px solid var(--line);border-radius:.4rem;word-break:break-all}
            .addr button{margin:0}
            ol{padding-left:1.25rem}
            li{margin:0 0 1rem}
            li strong{display:block}
            """;
    static final String SCRIPT =
            "(function(){var t=location.hash.slice(1);if(t){document.getElementById('link').value=t;history.replaceState(null,'',location.pathname);}})();";
    /** Copies the play address. The page works without it: the address is plain text anyone can select. */
    static final String COPY_SCRIPT =
            "(function(){var b=document.getElementById('copy'),a=document.getElementById('addr');if(!b||!a||!navigator.clipboard)return;"
            + "b.hidden=false;b.addEventListener('click',function(){navigator.clipboard.writeText(a.textContent).then(function(){b.textContent='Copied';});});})();";
    /**
     * Fills the invite code from an invite link, which carries it after # so it never reaches a server log (D187), then
     * takes it out of the address bar. The player still presses Continue: nothing is used up by opening a link.
     */
    static final String CODE_SCRIPT =
            "(function(){var t=location.hash.slice(1),c=document.getElementById('code');if(t&&c&&/^[A-Za-z0-9 -]{1,16}$/.test(t)){c.value=t;history.replaceState(null,'',location.pathname);}})();";
    static final String CSP = "default-src 'none'; style-src '" + sha256(CSS) + "'; script-src '" + sha256(SCRIPT) + "' '" + sha256(COPY_SCRIPT) + "' '" + sha256(CODE_SCRIPT)
            + "'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    /** The CSP for the welcome view: as {@link #CSP}, but its map button may post on to the map's own address. */
    static String cspWithMap(String mapUrl) {
        return mapUrl.isEmpty() ? CSP : CSP.replace("form-action 'self'", "form-action 'self' " + mapUrl);
    }

    private Html() {}

    public static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) switch (c) {
            case '<' -> b.append("&lt;");
            case '>' -> b.append("&gt;");
            case '&' -> b.append("&amp;");
            case '"' -> b.append("&quot;");
            case '\'' -> b.append("&#39;");
            default -> b.append(c);
        }
        return b.toString();
    }

    static String page(String title, String body, boolean withScript) {
        return page(title, body, withScript ? SCRIPT : null);
    }

    static String page(String title, String body, String script) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>" + esc(title) + "</title>"
                + "<style>" + CSS + "</style></head><body><main>" + body + "</main>"
                + (script != null ? "<script>" + script + "</script>" : "") + "</body></html>";
    }

    /** The server's logo, if it has one, then its name. The logo's alt text is the name, so it's never read twice. */
    static String header(PageSettings p) {
        String img = p.logo() == null ? "" : "<img class=\"logo\" src=\"" + p.base() + "/logo\" alt=\"" + esc(p.heading()) + "\">";
        return img + "<h1" + (p.logo() == null ? "" : " class=\"hint\"") + ">" + esc(p.heading()) + "</h1>";
    }

    /** A message line. Kind is "ok", "bad" or "" (plain). Announced to screen readers when the page loads. */
    static String message(String text, String kind) {
        if (text == null || text.isEmpty()) return "";
        return "<p role=\"status\"" + (kind.isEmpty() ? "" : " class=\"" + kind + "\"") + ">" + esc(text) + "</p>";
    }

    /** The page for someone not signed in: one box for the invite code. Nothing about the server's address is here. */
    static String home(PageSettings p, String csrf, boolean invitesOpen, boolean signInOpen, String msg, String msgKind) {
        StringBuilder b = new StringBuilder(header(p));
        b.append(message(msg, msgKind));
        if (invitesOpen) {
            b.append("<p>If someone invited you, type the code they gave you.</p>")
             .append("<form method=\"post\" action=\"").append(p.base()).append("/invite\">")
             .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf)).append("\">")
             .append("<label for=\"code\">Invite code</label>")
             .append("<input id=\"code\" name=\"code\" required maxlength=\"16\" autocomplete=\"off\" autocapitalize=\"characters\" spellcheck=\"false\" aria-describedby=\"code-hint\">")
             .append("<p id=\"code-hint\" class=\"hint\">Six letters and numbers. Dashes and spaces are fine.</p>")
             .append("<button type=\"submit\">Continue</button></form>");
        } else {
            b.append("<p>Invites aren't open on this server right now.</p>");
        }
        if (signInOpen) b.append("<hr><p class=\"hint\">Already playing? Type <strong>/login</strong> in game to sign this browser in.</p>");
        return page(p.heading(), b.toString(), invitesOpen ? CODE_SCRIPT : null);
    }

    /** After a code that matches an open invite: whose it is, and nothing used up until they say yes. */
    static String confirm(PageSettings p, String csrf, String name, String code) {
        StringBuilder b = new StringBuilder(header(p));
        b.append("<p>This invite is for <strong>").append(esc(name)).append("</strong>.</p>")
         .append("<p>Is that your Minecraft username? Using the invite puts that name on the server's list. Only that account can join with it.</p>")
         .append("<form method=\"post\" action=\"").append(p.base()).append("/invite/confirm\">")
         .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf)).append("\">")
         .append("<input type=\"hidden\" name=\"code\" value=\"").append(esc(code)).append("\">")
         .append("<button type=\"submit\">Yes, that's me</button></form>")
         .append("<p><a href=\"").append(p.base()).append("/\">No, go back</a></p>");
        return page(p.heading(), b.toString(), false);
    }

    /**
     * The signed-in view: the address to join and the first steps. This is the only page the play address is ever put
     * in, so it only reaches a browser with a valid session.
     */
    static String welcome(PageSettings p, String name, String csrf) {
        StringBuilder b = new StringBuilder(header(p));
        b.append("<p>Welcome, <strong>").append(esc(name)).append("</strong>. You're on the list.</p>");
        if (!p.playAddress().isEmpty()) {
            b.append("<h2>Join in Minecraft</h2>")
             .append("<p>In Minecraft (Java Edition), choose Multiplayer, then Add Server, and use this address:</p>")
             .append("<div class=\"addr\"><code id=\"addr\">").append(esc(p.playAddress())).append("</code>")
             .append("<button id=\"copy\" type=\"button\" hidden>Copy</button></div>");
        }
        if (!p.steps().isEmpty()) {
            b.append("<h2>Your first steps</h2><ol>");
            for (PageSettings.Step s : p.steps()) {
                b.append("<li><strong>").append(esc(s.title())).append("</strong>").append(esc(s.text())).append("</li>");
            }
            b.append("</ol>");
        }
        if (!p.mapUrl().isEmpty()) {
            b.append("<h2>The map</h2><form method=\"post\" action=\"" + p.base() + "/map\"><input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf))
             .append("\"><button type=\"submit\">Open the map</button></form>")
             .append("<p class=\"hint\">Signs this browser in on the map too. You see the worlds you can enter, and the players you could talk to in game.</p>");
        }
        b.append("<hr><form method=\"post\" action=\"").append(p.base()).append("/signout\"><input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf))
         .append("\"><button type=\"submit\">Sign out of this browser</button></form>");
        return page(p.heading(), b.toString(), COPY_SCRIPT);
    }

    static String signIn(PageSettings p, String csrf, boolean open, String msg, String msgKind) {
        StringBuilder b = new StringBuilder("<h1>Sign in</h1>");
        b.append(message(msg, msgKind));
        if (!open) {
            b.append("<p>Sign-in isn't open on this server right now.</p>");
            return page("Sign in", b.toString(), false);
        }
        b.append("<form method=\"post\" action=\"").append(p.base()).append("/signin\">")
         .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf)).append("\">")
         .append("<label for=\"link\">Sign-in link</label>")
         .append("<input id=\"link\" name=\"link\" required maxlength=\"300\" autocomplete=\"off\" spellcheck=\"false\" aria-describedby=\"link-hint\">")
         .append("<p id=\"link-hint\" class=\"hint\">Filled in for you when you click the link in game. If it's empty, paste the link here.</p>")
         .append("<button type=\"submit\">Sign in</button></form>");
        return page("Sign in", b.toString(), true);
    }

    static String plain(String title, String text) {
        return page(title, "<h1>" + esc(title) + "</h1>" + message(text, ""), false);
    }

    static String sha256(String s) {
        try {
            return "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
