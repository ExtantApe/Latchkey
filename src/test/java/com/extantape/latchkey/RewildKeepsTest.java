package com.extantape.latchkey;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What this plugin tells Rewild, through stand-ins shaped exactly like Rewild's KeepProvider before and after 0.3.0. */
class RewildKeepsTest {
    /** Same shape as com.extantape.rewild.api.KeepProvider in Rewild 0.3.0. */
    public interface Provider030 {
        record Keep(int chunkX, int chunkZ, String reason) {}
        record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason) {}
        Collection<Keep> keeps(World world);
        default Collection<Area> areas(World world) {
            return List.of();
        }
    }

    /** Rewild 0.2: whole chunks only. */
    public interface Provider020 {
        record Keep(int chunkX, int chunkZ, String reason) {}
        Collection<Keep> keeps(World world);
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (p, m, a) -> m.getName().equals("getName") ? name : null);
    }

    static final List<RewildKeeps.Chunk> CHUNKS = List.of(new RewildKeeps.Chunk(1, -2, "the station"));
    static final List<RewildKeeps.Box> BOXES = List.of(new RewildKeeps.Box(1, 60, 1, 5, 70, 3, "a gate"));

    @Test void chunksAndBoxesReachRewild030() throws Exception {
        Provider030 p = (Provider030) RewildKeeps.provider(Provider030.class, w -> CHUNKS, w -> BOXES);
        assertEquals(List.of(new Provider030.Keep(1, -2, "the station")), List.copyOf(p.keeps(world("w"))));
        assertEquals(List.of(new Provider030.Area(1, 60, 1, 5, 70, 3, "a gate")), List.copyOf(p.areas(world("w"))));
    }

    @Test void olderRewildGetsChunksOnly() throws Exception {
        Provider020 p = (Provider020) RewildKeeps.provider(Provider020.class, w -> CHUNKS, w -> BOXES);
        assertEquals(List.of(new Provider020.Keep(1, -2, "the station")), List.copyOf(p.keeps(world("w"))));
    }

    @Test void aFailureReachesRewildSoItResetsNothing() throws Exception {
        Provider030 p = (Provider030) RewildKeeps.provider(Provider030.class, w -> { throw new IllegalStateException("broken"); }, w -> BOXES);
        assertThrows(IllegalStateException.class, () -> p.keeps(world("w")));
    }
}
