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
            """;
    static final String SCRIPT =
            "(function(){var t=location.hash.slice(1);if(t){document.getElementById('link').value=t;history.replaceState(null,'',location.pathname);}})();";
    static final String CSP = "default-src 'none'; style-src '" + sha256(CSS) + "'; script-src '" + sha256(SCRIPT)
            + "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

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
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>" + esc(title) + "</title>"
                + "<style>" + CSS + "</style></head><body><main>" + body + "</main>"
                + (withScript ? "<script>" + SCRIPT + "</script>" : "") + "</body></html>";
    }

    /** A message line. Kind is "ok", "bad" or "" (plain). Announced to screen readers when the page loads. */
    static String message(String text, String kind) {
        if (text == null || text.isEmpty()) return "";
        return "<p role=\"status\"" + (kind.isEmpty() ? "" : " class=\"" + kind + "\"") + ">" + esc(text) + "</p>";
    }

    static String home(String signedInAs, String csrf, boolean invitesOpen, String msg, String msgKind) {
        StringBuilder b = new StringBuilder("<h1>Join the server</h1>");
        b.append(message(msg, msgKind));
        if (invitesOpen) {
            b.append("<p>If someone invited you, type the code they gave you and your Minecraft username.</p>")
             .append("<form method=\"post\" action=\"/invite\">")
             .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf)).append("\">")
             .append("<label for=\"code\">Invite code</label>")
             .append("<input id=\"code\" name=\"code\" required maxlength=\"16\" autocomplete=\"off\" autocapitalize=\"characters\" spellcheck=\"false\" aria-describedby=\"code-hint\">")
             .append("<p id=\"code-hint\" class=\"hint\">Six letters and numbers. Dashes and spaces are fine.</p>")
             .append("<label for=\"name\">Minecraft username</label>")
             .append("<input id=\"name\" name=\"name\" required maxlength=\"16\" autocomplete=\"username\" autocapitalize=\"off\" spellcheck=\"false\">")
             .append("<button type=\"submit\">Use invite</button></form>");
        } else {
            b.append("<p>Invites aren't open on this server right now.</p>");
        }
        b.append("<hr>");
        if (signedInAs != null) {
            b.append("<p>Your browser is signed in as <strong>").append(esc(signedInAs)).append("</strong>.</p>")
             .append("<form method=\"post\" action=\"/signout\"><input type=\"hidden\" name=\"csrf\" value=\"").append(esc(csrf))
             .append("\"><button type=\"submit\">Sign out</button></form>");
        } else {
            b.append("<p class=\"hint\">Already playing? Type <strong>/signin</strong> in game to sign this browser in.</p>");
        }
        return page("Join the server", b.toString(), false);
    }

    static String signIn(String csrf, boolean open, String msg, String msgKind) {
        StringBuilder b = new StringBuilder("<h1>Sign in</h1>");
        b.append(message(msg, msgKind));
        if (!open) {
            b.append("<p>Sign-in isn't open on this server right now.</p>");
            return page("Sign in", b.toString(), false);
        }
        b.append("<form method=\"post\" action=\"/signin\">")
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
