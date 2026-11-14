package com.extantape.latchkey.web;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The page's colors and font, from {@code page.theme}. Any server can set one; nothing about a particular server is in
 * Latchkey's code. Every color pair the page uses is checked against WCAG 2.2 AA in light and dark before the theme is
 * used. If one fails, the whole theme is left out with a line saying which pair and by how much, and the page keeps its
 * own colors (fails closed).
 */
public record Theme(Map<String, String> light, Map<String, String> dark, String font, String mode) {
    /** Which colors the page uses: the device's choice ("auto"), or always "dark" or always "light". */
    static final Set<String> MODES = Set.of("auto", "dark", "light");
    /** Setting names, in the order they're written, with the CSS variable each sets. */
    static final Map<String, String> VARS = orderedVars();
    static final Map<String, String> LIGHT = Map.of("background", "#ffffff", "text", "#1a1a1a", "muted", "#4a4a4a", "line", "#767676",
            "button", "#0b5394", "button-text", "#ffffff", "bad", "#a4161a", "ok", "#1b5e20");
    static final Map<String, String> DARK = Map.of("background", "#141414", "text", "#f2f2f2", "muted", "#c4c4c4", "line", "#8a8a8a",
            "button", "#9fc5f8", "button-text", "#0a0a0a", "bad", "#ff9a9a", "ok", "#9be29f");
    static final Map<String, String> FONTS = Map.of(
            "system", "system-ui,sans-serif",
            "rounded", "ui-rounded,\"SF Pro Rounded\",system-ui,sans-serif",
            "serif", "ui-serif,Georgia,serif",
            "mono", "ui-monospace,monospace");

    /** What each pair is for, and the contrast it needs. Links use the button color as text, so that's 4.5 too. */
    record Pair(String fg, String bg, double need, String what) {}
    static final List<Pair> PAIRS = List.of(
            new Pair("text", "background", 4.5, "text"),
            new Pair("muted", "background", 4.5, "hints"),
            new Pair("button-text", "button", 4.5, "button labels"),
            new Pair("button", "background", 4.5, "links"),
            new Pair("line", "background", 3.0, "input borders"),
            new Pair("bad", "background", 4.5, "error messages"),
            new Pair("ok", "background", 4.5, "success messages"));

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> KEYS = Set.of("light", "dark", "font", "mode");

    private static Map<String, String> orderedVars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("background", "--bg"); m.put("text", "--fg"); m.put("muted", "--muted"); m.put("line", "--line");
        m.put("button", "--btn"); m.put("button-text", "--btnfg"); m.put("bad", "--bad"); m.put("ok", "--ok");
        return m;
    }

    /** The theme, or null when there's none or it's left out; each reason goes into problems. */
    static Theme read(ConfigurationSection t, List<String> problems) {
        if (t == null) return null;
        boolean bad = false;
        for (String k : t.getKeys(false)) if (!KEYS.contains(k)) { problems.add("page.theme." + k + " isn't a setting Latchkey knows, so the page keeps its own colors."); bad = true; }
        Map<String, String> light = mode(t.getConfigurationSection("light"), LIGHT, "light", problems);
        Map<String, String> dark = mode(t.getConfigurationSection("dark"), DARK, "dark", problems);
        if (light == null || dark == null) bad = true;
        String font = t.getString("font", "system").strip().toLowerCase(Locale.ROOT);
        if (!FONTS.containsKey(font)) { problems.add("page.theme.font is \"" + font + "\". Use system, rounded, serif or mono, so the page keeps its own look."); bad = true; }
        String mode = t.getString("mode", "auto").strip().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) { problems.add("page.theme.mode is \"" + mode + "\". Use auto, dark or light, so the page keeps its own look."); bad = true; }
        if (bad) return null;
        // Only the colors the page can show are checked: an always-dark page never shows its light colors.
        List<Map.Entry<String, Map<String, String>>> shown = switch (mode) {
            case "dark" -> List.of(Map.entry("dark", dark));
            case "light" -> List.of(Map.entry("light", light));
            default -> List.of(Map.entry("light", light), Map.entry("dark", dark));
        };
        for (var mode0 : shown) {
            var modeEntry = mode0;
            for (Pair p : PAIRS) {
                double r = ratio(modeEntry.getValue().get(p.fg()), modeEntry.getValue().get(p.bg()));
                if (r < p.need()) {
                    problems.add(String.format(Locale.ROOT, "page.theme %s: %s %s on %s %s is %.2f:1, and %s need %.1f:1. The page keeps its own colors until it passes.",
                            modeEntry.getKey(), p.fg(), modeEntry.getValue().get(p.fg()), p.bg(), modeEntry.getValue().get(p.bg()), r, p.what(), p.need()));
                    bad = true;
                }
            }
        }
        return bad ? null : new Theme(light, dark, font, mode);
    }

    private static Map<String, String> mode(ConfigurationSection s, Map<String, String> defaults, String name, List<String> problems) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : VARS.keySet()) out.put(k, defaults.get(k));
        if (s == null) return out;
        boolean ok = true;
        for (String k : s.getKeys(false)) {
            String v = s.getString(k, "").strip();
            if (!VARS.containsKey(k)) { problems.add("page.theme." + name + "." + k + " isn't a color Latchkey knows, so the page keeps its own colors."); ok = false; }
            else if (!HEX.matcher(v).matches()) { problems.add("page.theme." + name + "." + k + " is \"" + v + "\". Write colors as #rrggbb, so the page keeps its own colors."); ok = false; }
            else out.put(k, v.toLowerCase(Locale.ROOT));
        }
        return ok ? out : null;
    }

    /** The stylesheet at {@code /theme.css}: both modes in full, so a theme never mixes with the page's own colors. */
    public String css() {
        String body = "body{font-family:" + FONTS.get(font) + "}\n";
        return switch (mode) {
            // A fixed mode also sets color-scheme, so form controls and scrollbars match whatever the device prefers.
            case "dark" -> ":root{color-scheme:dark;" + vars(dark) + "}\n" + body;
            case "light" -> ":root{color-scheme:light;" + vars(light) + "}\n" + body;
            default -> ":root{" + vars(light) + "}\n@media (prefers-color-scheme:dark){:root{" + vars(dark) + "}}\n" + body;
        };
    }

    private static String vars(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        VARS.forEach((k, v) -> b.append(v).append(':').append(m.get(k)).append(';'));
        return b.toString();
    }

    static double ratio(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
