package com.extantape.latchkey.web;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** page: in config.yml. Nothing here is a safety setting, so a mistake leaves that part out and says why. */
class PageSettingsTest {
    @TempDir Path dir;
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    PageSettings read(String yaml) {
        return PageSettings.read(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("page"), dir);
    }

    @Test void theShippedDefaultsReadCleanly() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            var c = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
            var p = PageSettings.read(c.getConfigurationSection("page"), dir);
            assertEquals(java.util.List.of(), p.problems());
            assertEquals("Join the server", p.heading());
            assertEquals("", p.playAddress(), "no address until the server sets one");
            assertEquals(2, p.steps().size());
        }
    }

    @Test void aPngOrWebpLogoIsReadByItsBytesNotItsName() throws Exception {
        Files.write(dir.resolve("logo.png"), PNG);
        Files.write(dir.resolve("logo.webp"), WEBP);
        Files.write(dir.resolve("fake.png"), "<svg onload=alert(1)>".getBytes());
        assertEquals("image/png", read("page: {logo: logo.png}").logoType());
        assertEquals("image/webp", read("page: {logo: logo.webp}").logoType());
        var fake = read("page: {logo: fake.png}");
        assertNull(fake.logo());
        assertTrue(fake.problems().getFirst().contains("isn't a PNG or WebP"));
    }

    @Test void theLogoComesOnlyFromThePluginFolder() {
        for (String name : new String[] {"../secret.png", "/etc/passwd.png", "sub/logo.png", "logo.svg", "logo.png.exe"}) {
            var p = read("page: {logo: '" + name + "'}");
            assertNull(p.logo(), name);
            assertFalse(p.problems().isEmpty(), name);
        }
    }

    @Test void aBigLogoIsRefused() throws Exception {
        byte[] big = new byte[PageSettings.MAX_LOGO_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        Files.write(dir.resolve("big.png"), big);
        assertNull(read("page: {logo: big.png}").logo());
    }

    @Test void thePlayAddressMustLookLikeOne() {
        assertEquals("play.example.test", read("page: {play-address: play.example.test}").playAddress());
        assertEquals("play.example.test:25566", read("page: {play-address: 'play.example.test:25566'}").playAddress());
        for (String bad : new String[] {"<b>x</b>", "a b", "javascript:alert(1)", "x".repeat(300)}) {
            assertEquals("", read("page: {play-address: '" + bad + "'}").playAddress(), bad);
        }
    }

    @Test void stepsAreCheckedAndCapped() {
        StringBuilder y = new StringBuilder("page:\n  steps:\n");
        for (int i = 0; i < 10; i++) y.append("    - {title: 'Step ").append(i).append("', text: 'Do it.'}\n");
        y.append("    - {title: '', text: 'no title'}\n");
        var p = read(y.toString());
        assertEquals(PageSettings.MAX_STEPS, p.steps().size());
        assertFalse(p.problems().isEmpty());
    }

    @Test void unknownSettingsAreNamed() {
        var p = read("page: {debug: true, title: Example Realm}");
        assertEquals("Example Realm", p.heading());
        assertTrue(p.problems().stream().anyMatch(s -> s.contains("page.debug")));
    }

    @Test void theMapAddressMustBeAPlainHttpsHost() {
        assertEquals("https://map.example.org", read("page: {map-address: 'https://map.example.org/'}").mapUrl());
        assertEquals("https://map.example.org:8443", read("page: {map-address: 'https://map.example.org:8443'}").mapUrl());
        for (String bad : new String[]{"http://map.example.org", "https://map.example.org/x", "javascript:alert(1)",
                "https://map.example.org?x=1", "https://map.example.org/#x", "map.example.org", "https://a b"}) {
            var p = read("page: {map-address: '" + bad + "'}");
            assertEquals("", p.mapUrl(), bad);
            assertFalse(p.problems().isEmpty(), bad);
        }
        assertEquals("", read("page: {}").mapUrl());
    }

    // --- page.theme (K632): any server's colors, checked for contrast, failing closed ---

    @Test void aThemeThatPassesIsUsedInBothModesInFull() {
        var p = read("""
                page:
                  theme:
                    light: {background: "#FBF4E8", text: "#1e1026", button: "#a33a6b"}
                    dark: {background: "#1e1026", text: "#f7ead8", muted: "#f0c487", button: "#ffc93c", button-text: "#1e1026", ok: "#7cc35a"}
                    font: rounded
                """);
        assertEquals(java.util.List.of(), p.problems());
        String css = p.theme().css();
        assertTrue(css.startsWith(":root{--bg:#fbf4e8;--fg:#1e1026;--muted:#4a4a4a;"), css);
        assertTrue(css.contains("@media (prefers-color-scheme:dark){:root{--bg:#1e1026;--fg:#f7ead8;--muted:#f0c487;--line:#8a8a8a;--btn:#ffc93c;--btnfg:#1e1026;"), css);
        assertTrue(css.contains("ui-rounded"));
    }

    @Test void aPairBelowContrastLeavesTheWholeThemeOutAndSaysWhich() {
        var p = read("page:\n  theme:\n    light: {button: \"#f0c487\"}\n");
        assertNull(p.theme());
        assertTrue(p.problems().stream().anyMatch(x -> x.contains("light") && x.contains("button-text") && x.contains("button #f0c487") && x.contains("4.5:1")), p.problems().toString());
    }

    @Test void darkModeIsCheckedToo() {
        var p = read("page:\n  theme:\n    dark: {text: \"#333333\"}\n");
        assertNull(p.theme());
        assertTrue(p.problems().stream().anyMatch(x -> x.startsWith("page.theme dark: text")), p.problems().toString());
    }

    @Test void badColorsUnknownNamesAndFontsLeaveTheThemeOut() {
        for (String y : new String[]{
                "page:\n  theme:\n    light: {text: \"red\"}\n",
                "page:\n  theme:\n    light: {text: \"#12345\"}\n",
                "page:\n  theme:\n    light: {text: \"#000000;}body{display:none\"}\n",
                "page:\n  theme:\n    light: {shadow: \"#000000\"}\n",
                "page:\n  theme:\n    font: comic\n",
                "page:\n  theme:\n    css: \"body{}\"\n"}) {
            var p = read(y);
            assertNull(p.theme(), y);
            assertFalse(p.problems().isEmpty(), y);
        }
    }

    @Test void noThemeMeansLatchkeysOwnColors() {
        assertNull(read("page: {title: X}").theme());
    }

    @Test void contrastIsMeasuredTheWcagWay() {
        assertEquals(21.0, Theme.ratio("#000000", "#ffffff"), 0.01);
        assertEquals(1.0, Theme.ratio("#777777", "#777777"), 0.001);
        assertEquals(4.48, Theme.ratio("#777777", "#ffffff"), 0.01);
    }

    @Test void latchkeysOwnColorsPassItsOwnChecks() {
        var problems = new java.util.ArrayList<String>();
        assertNotNull(Theme.read(YamlConfiguration.loadConfiguration(new StringReader("theme: {font: system}")).getConfigurationSection("theme"), problems));
        assertEquals(java.util.List.of(), problems);
    }

    @Test void anAlwaysDarkThemeUsesAndChecksOnlyItsDarkColors() {
        var p = read("page:\n  theme:\n    mode: dark\n    light: {button: \"#f0c487\"}\n    dark: {background: \"#1e1026\", text: \"#f7ead8\"}\n");
        assertEquals(java.util.List.of(), p.problems(), "the light colors never show, so they aren't held against it");
        String css = p.theme().css();
        assertTrue(css.startsWith(":root{color-scheme:dark;--bg:#1e1026;--fg:#f7ead8;"), css);
        assertFalse(css.contains("prefers-color-scheme"), "the device's choice doesn't switch it");
        assertFalse(css.contains("#f0c487"));
    }

    @Test void anAlwaysDarkThemeStillFailsClosedOnItsDarkColors() {
        var p = read("page:\n  theme:\n    mode: dark\n    dark: {text: \"#333333\"}\n");
        assertNull(p.theme());
    }

    @Test void anUnknownModeLeavesTheThemeOut() {
        var p = read("page:\n  theme:\n    mode: dusk\n");
        assertNull(p.theme());
        assertTrue(p.problems().stream().anyMatch(x -> x.contains("page.theme.mode")));
    }
}
