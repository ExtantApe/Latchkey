package com.extantape.latchkey;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tells Rewild, if it's installed, what this plugin owns, so Rewild never rewilds it: whole chunks to leave alone, and
 * block boxes to keep in place while the land around them rewilds. Rewild's interface is reached by name through its
 * own class loader, so neither plugin needs the other. The same small class is in each ExtantApe plugin that owns
 * blocks; keep the copies alike.
 *
 * <p>Rewild only listens once an admin respects this plugin (/rewild plugins). If anything here throws, Rewild resets
 * nothing in that world, which is the safe side.
 */
public final class RewildKeeps implements Listener {
    static final String PROVIDER = "com.extantape.rewild.api.KeepProvider";

    /** A box of blocks, corners included, with the reason /rewild why shows. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason) {}

    /** A chunk to keep whole, with its reason. */
    public record Chunk(int x, int z, String reason) {}

    private final Plugin owner;
    private final Function<World, List<Chunk>> chunks;
    private final Function<World, List<Box>> boxes;
    private boolean registered;

    public RewildKeeps(Plugin owner, Function<World, List<Chunk>> chunks, Function<World, List<Box>> boxes) {
        this.owner = owner;
        this.chunks = chunks;
        this.boxes = boxes;
    }

    /** Registers now if Rewild is already running; otherwise when it enables. */
    public void start() {
        Plugin rewild = Bukkit.getPluginManager().getPlugin("Rewild");
        if (rewild != null && rewild.isEnabled()) register(rewild);
    }

    @EventHandler
    public void onEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("Rewild")) register(e.getPlugin());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(Plugin rewild) {
        if (registered) return;
        try {
            ClassLoader cl = rewild.getClass().getClassLoader();
            Class<?> iface = Class.forName(PROVIDER, true, cl);
            Object provider = provider(iface, chunks, boxes);
            Bukkit.getServicesManager().register((Class) iface, provider, owner, ServicePriority.Normal);
            registered = true;
            owner.getLogger().info("Telling Rewild what " + owner.getName() + " owns, so it's never rewilded.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            owner.getLogger().warning("Rewild is installed but its keep interface doesn't match what " + owner.getName()
                    + " expects (" + e + "). If an admin respects " + owner.getName() + ", Rewild will reset nothing.");
        }
    }

    /** A KeepProvider for the given interface. Package-private for tests, which pass a stand-in of the same shape. */
    static Object provider(Class<?> iface, Function<World, List<Chunk>> chunks, Function<World, List<Box>> boxes)
            throws ReflectiveOperationException {
        Class<?> keepClass = Class.forName(iface.getName() + "$Keep", true, iface.getClassLoader());
        Constructor<?> keep = keepClass.getDeclaredConstructor(int.class, int.class, String.class);
        Constructor<?> area;
        try {
            Class<?> areaClass = Class.forName(iface.getName() + "$Area", true, iface.getClassLoader());
            area = areaClass.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class, String.class);
        } catch (ClassNotFoundException e) {
            area = null; // Rewild before 0.3.0: whole chunks only.
        }
        Constructor<?> makeArea = area;
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> switch (method.getName()) {
            case "keeps" -> {
                List<Object> out = new ArrayList<>();
                for (Chunk c : chunks.apply((World) args[0])) out.add(keep.newInstance(c.x(), c.z(), c.reason()));
                yield out;
            }
            case "areas" -> {
                List<Object> out = new ArrayList<>();
                if (makeArea != null) {
                    for (Box b : boxes.apply((World) args[0])) {
                        out.add(makeArea.newInstance(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.reason()));
                    }
                }
                yield out;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> owner(proxy);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static String owner(Object proxy) {
        return "keeps for Rewild";
    }
}
