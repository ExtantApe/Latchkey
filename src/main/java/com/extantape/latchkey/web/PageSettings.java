package com.extantape.latchkey.web;

import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How the landing page looks and what it tells a player after their invite is used: the server's name, a logo, the
 * address to join, and a few first steps. All of it is optional. A setting that's wrong is left out with a console
 * line saying why; the page itself keeps working, because none of this is a safety setting.
 *
 * <p>The map address is where "Open the map" sends a signed-in player, with a one-time token (Mapkeeper, D43).
 *
 * <p>The play address is only ever put in the page of a signed-in browser (see {@link Html#welcome}).
 */
public record PageSettings(String title, byte[] logo, String logoType, String playAddress, List<Step> steps, String mapUrl, List<String> problems, String base, Theme theme) {
    public record Step(String title, String text) {}

    public PageSettings(String title, byte[] logo, String logoType, String playAddress, List<Step> steps, String mapUrl, List<String> problems) {
        this(title, logo, logoType, playAddress, steps, mapUrl, problems, "", null);
    }

    /** The same page, served under a path such as "/join" ("" for the root). Set from landing.public-url. */
    public PageSettings withBase(String b) { return new PageSettings(title, logo, logoType, playAddress, steps, mapUrl, problems, b, theme); }

    static final int MAX_LOGO_BYTES = 512 * 1024;
    static final int MAX_STEPS = 8;
    private static final Set<String> KEYS = Set.of("title", "logo", "play-address", "steps", "map-address", "theme");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    private static final Pattern MAP = Pattern.compile("https://[a-z0-9.-]{1,253}(:[0-9]{1,5})?");
    private static final Pattern LOGO_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}\\.(png|webp)");

    public static PageSettings none() { return new PageSettings("", null, null, "", List.of(), "", List.of()); }

    /** Reads {@code page:} from config.yml. The logo is read from the plugin's own folder, never from anywhere else. */
    public static PageSettings read(ConfigurationSection page, Path dataFolder) {
        if (page == null) return none();
        List<String> problems = new ArrayList<>();
        for (String k : page.getKeys(false)) if (!KEYS.contains(k)) problems.add("page." + k + " isn't a setting Latchkey knows, so it's ignored.");

        String title = page.getString("title", "").strip();
        if (title.length() > 60) { problems.add("page.title is longer than 60 characters, so the page uses \"Join the server\"."); title = ""; }

        byte[] logo = null;
        String logoType = null;
        String logoName = page.getString("logo", "").strip();
        if (!logoName.isEmpty()) {
            if (!LOGO_NAME.matcher(logoName).matches()) {
                problems.add("page.logo is \"" + logoName + "\". It must be the name of a .png or .webp file in plugins/Latchkey/, with no folders, so the page shows no logo.");
            } else {
                try {
                    Path file = dataFolder.resolve(logoName);
                    long size = Files.size(file);
                    if (size > MAX_LOGO_BYTES) {
                        problems.add("page.logo is " + (size / 1024) + " KB. Use one of at most 512 KB, so the page loads fast. No logo for now.");
                    } else {
                        byte[] bytes = Files.readAllBytes(file);
                        logoType = typeOf(bytes);
                        if (logoType == null) problems.add("page.logo isn't a PNG or WebP image, so the page shows no logo.");
                        else logo = bytes;
                    }
                } catch (IOException e) {
                    problems.add("page.logo \"" + logoName + "\" couldn't be read from plugins/Latchkey/, so the page shows no logo.");
                }
            }
        }

        String address = page.getString("play-address", "").strip();
        if (!address.isEmpty() && !ADDRESS.matcher(address).matches()) {
            problems.add("page.play-address \"" + address + "\" isn't a host name with an optional :port, so it isn't shown.");
            address = "";
        }

        List<Step> steps = new ArrayList<>();
        for (Map<?, ?> m : page.getMapList("steps")) {
            Object t = m.get("title"), x = m.get("text");
            String st = t == null ? "" : t.toString().strip(), sx = x == null ? "" : x.toString().strip();
            if (st.isEmpty() || st.length() > 80 || sx.length() > 600) {
                problems.add("A step in page.steps needs a title of up to 80 characters and text of up to 600, so one is left out.");
                continue;
            }
            if (steps.size() == MAX_STEPS) { problems.add("page.steps has more than " + MAX_STEPS + " steps; the rest are left out."); break; }
            steps.add(new Step(st, sx));
        }
        String map = page.getString("map-address", "").strip();
        if (map.endsWith("/")) map = map.substring(0, map.length() - 1);
        if (!map.isEmpty() && !MAP.matcher(map).matches()) {
            problems.add("page.map-address \"" + map + "\" isn't an https:// address with only a host name and an optional :port, so the page has no map button.");
            map = "";
        }
        Theme theme = Theme.read(page.getConfigurationSection("theme"), problems);
        return new PageSettings(title, logo, logoType, address, List.copyOf(steps), map, List.copyOf(problems), "", theme);
    }

    /** "image/png" or "image/webp" from the file's own first bytes, never its name. Null for anything else. */
    static String typeOf(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    public String heading() { return title.isEmpty() ? "Join the server" : title; }
}
