package com.extantape.latchkey;

import com.destroystokyo.paper.event.server.WhitelistToggleEvent;
import com.extantape.latchkey.api.InviteRedeemedEvent;
import com.extantape.latchkey.cmd.Caller;
import com.extantape.latchkey.cmd.Host;
import com.extantape.latchkey.cmd.LatchkeyCommands;
import com.extantape.signalbox.OnceFiles;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.command.CommandSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.invite.InviteStore;
import com.extantape.latchkey.session.ActivityTracker;
import com.extantape.latchkey.session.SessionStore;
import org.bukkit.Location;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.extantape.latchkey.setup.SafeSetup;
import com.extantape.latchkey.setup.SafeSetup.Part;
import com.extantape.latchkey.web.Gate;
import com.extantape.latchkey.web.LandingServer;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class LatchkeyPlugin extends JavaPlugin implements Listener, Gate, Host {
    /** Bots can't log in to an online-mode server. Only something that controls the server's launch can set this. */
    private static final String TESTING_FLAG = "latchkey.allowOfflineForTesting";
    private static final String PERMISSION = "latchkey.signin";
    /** How often sessions are checked against the whitelist and ban list, in ticks. Catches bans, which fire no event. */
    private static final long RECHECK_TICKS = 20L * 30;

    private SignInLinks links;
    private InviteStore invites;
    private SessionStore sessions;
    private volatile SafeSetup.Result setup;
    private volatile boolean whitelistOn;
    private LandingServer landing;
    private OnceFiles once;
    private com.extantape.latchkey.lobby.Lobby lobby;
    private final ActivityTracker activity = new ActivityTracker(System::currentTimeMillis);
    private LatchkeyCommands commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        links = new SignInLinks(getDataFolder().toPath().resolve("pending"));
        clearLinks();
        try {
            invites = new InviteStore(getDataFolder().toPath(), System::currentTimeMillis);
            sessions = new SessionStore(getDataFolder().toPath().resolve("sessions"), System::currentTimeMillis);
        } catch (IOException | RuntimeException e) {
            // Without its files Latchkey can't keep a promise about who's in, so it does nothing at all.
            getLogger().log(Level.SEVERE, "Couldn't read Latchkey's files, so invites, sign-in and the landing page are off.", e);
            return;
        }
        once = new OnceFiles(getDataFolder().toPath());
        try { once.clearAll(); } catch (IOException e) { getLogger().log(Level.WARNING, "Couldn't clear old once-files", e); }
        commands = new LatchkeyCommands(this, getLogger());
        whitelistOn = Bukkit.hasWhitelist();
        getServer().getPluginManager().registerEvents(this, this);
        lobby = new com.extantape.latchkey.lobby.Lobby(this);
        getServer().getPluginManager().registerEvents(lobby, this);
        applySetup();
        Bukkit.getScheduler().runTaskTimer(this, this::recheckSessions, RECHECK_TICKS, RECHECK_TICKS);
        // Brigadier: players without the permission aren't sent the command at all.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("signin")
                        .requires(source -> source.getSender().hasPermission(PERMISSION))
                        .executes(context -> {
                            signIn(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Get a one-time link that signs your browser in."));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("latchkey")
                        .requires(source -> source.getSender().hasPermission(LatchkeyCommands.P_STATUS)
                                || source.getSender().hasPermission(LatchkeyCommands.P_CREATE)
                                || source.getSender().hasPermission(LatchkeyCommands.P_LIST))
                        .executes(context -> {
                            commands.run(caller(context.getSource().getSender()), new String[0]);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    String typed = builder.getRemaining();
                                    for (var d : LatchkeyCommands.described()) {
                                        if (d.name().startsWith(typed) && context.getSource().getSender().hasPermission(d.permission()))
                                            builder.suggest(d.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String raw = StringArgumentType.getString(context, "args").trim();
                                    commands.run(caller(context.getSource().getSender()), raw.isEmpty() ? new String[0] : raw.split(" +"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Invites, browser sessions and the landing page.", List.of("lk")));
    }

    private static Caller caller(CommandSender s) {
        return new Caller() {
            public boolean has(String permission) { return s.hasPermission(permission); }
            public boolean inGame() { return s instanceof Player; }
            public void tell(String text, boolean problem) { s.sendMessage(Component.text(text, problem ? NamedTextColor.RED : NamedTextColor.WHITE)); }
            public void line(String raw) { s.sendMessage(raw); }
        };
    }

    // --- Host: what the commands ask of the plugin ---

    @Override
    public CompletableFuture<Optional<Profile>> resolve(String nameOrUuid) {
        CompletableFuture<Optional<Profile>> f = new CompletableFuture<>();
        PlayerProfile profile = nameOrUuid.contains("-")
                ? Bukkit.createProfile(UUID.fromString(nameOrUuid)) : Bukkit.createProfile(nameOrUuid);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean found = profile.complete(false);
                f.complete(found && profile.getId() != null && profile.getName() != null
                        ? Optional.of(new Profile(profile.getId(), profile.getName())) : Optional.empty());
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    @Override
    public ProfileCheck checkProfiles(UUID uuid, String name, List<String> profiles, java.util.Set<String> confirmed) {
        var e = new com.extantape.latchkey.api.InviteCheckEvent(uuid, name, profiles, confirmed);
        getServer().getPluginManager().callEvent(e);
        return new ProfileCheck(e.getRefusalCode(), e.getRefusalMessage(), e.getAsked(), e.getUnanswered(), e.getCheckedBy());
    }

    @Override public InviteStore invites() { return invites; }
    @Override public SessionStore sessions() { return sessions; }
    @Override public OnceFiles once() { return once; }
    @Override public Duration defaultInviteLifetime() { return setup.settings().inviteLifetime(); }
    @Override public boolean redemptionOpen() { return landing != null && allowed(Part.REDEMPTION); }
    @Override public String version() { return getPluginMeta().getVersion(); }
    @Override public void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(this, r);
    }
    @Override public void reload() { applySetup(); }

    @Override
    public Status status() {
        SafeSetup.Result r = setup;
        List<String> problems = new ArrayList<>();
        for (SafeSetup.Problem p : r.problems()) problems.add(p.line());
        if (!whitelistOn) problems.add("The server's whitelist is off, so anyone could join and sign in. Fix: turn it on with /whitelist on.");
        String lobbyProblem = lobby == null ? null : lobby.problem();
        if (lobbyProblem != null) problems.add(lobbyProblem);
        return new Status(lobby != null && lobby.location() != null, landing != null, redemptionOpen(), landing != null && allowed(Part.SIGNIN), whitelistOn,
                landing != null ? r.settings().publicUrl().toString() : null, problems);
    }

    @Override
    public void onDisable() {
        stopLanding();
        if (links != null) clearLinks();
    }

    /** Runs every safe-setup check and starts only the parts that pass. Called at startup and on reload. */
    void applySetup() {
        stopLanding();
        reloadConfig();
        SafeSetup.Result r = SafeSetup.check(getConfig(), true, identitiesProven());
        if (lobby != null) lobby.apply(com.extantape.latchkey.lobby.LobbySettings.read(getConfig().getConfigurationSection("lobby")));
        setup = r;
        for (SafeSetup.Problem p : r.problems()) getLogger().warning(p.line());
        if (!whitelistOn) getLogger().warning("The server's whitelist is off, so anyone could join and sign in. Fix: turn it on with /whitelist on.");
        if (!r.allowed(Part.LANDING)) return;
        landing = new LandingServer(r.settings(), invites, sessions, links, this, getLogger(), System::currentTimeMillis);
        try {
            landing.start();
            getLogger().info("Landing page listening on " + r.settings().bind() + ":" + r.settings().port() + " for " + r.settings().publicUrl());
        } catch (IOException e) {
            getLogger().warning("The landing page couldn't start on " + r.settings().bind() + ":" + r.settings().port()
                    + " (" + e.getMessage() + "). Fix: pick a free port in landing.port.");
            landing = null;
        }
    }

    private void stopLanding() {
        if (landing != null) landing.stop();
        landing = null;
    }

    /**
     * Where the lobby is right now, or null if it's off or its world isn't loaded. Part of the contract other plugins
     * read by name (crossing latchkey-waystation), so a gate can be placed there before anyone has joined.
     */
    public org.bukkit.Location lobbyLocation() {
        return lobby == null ? null : lobby.location();
    }

    // --- Gate: what the web page asks of the server ---

    @Override
    public boolean allowed(Part part) {
        SafeSetup.Result r = setup;
        if (r == null || !r.allowed(part)) return false;
        return part == Part.LANDING || whitelistOn;
    }

    @Override
    public CompletableFuture<Boolean> admit(Invite invite) {
        return onServerThread(() -> {
            if (!allowed(Part.REDEMPTION)) return false;
            OfflinePlayer p = Bukkit.getOfflinePlayer(invite.uuid());
            p.setWhitelisted(true);
            getServer().getPluginManager().callEvent(new InviteRedeemedEvent(invite.uuid(), invite.name(), invite.profiles(), invite.confirmed()));
            getLogger().info("Invite " + invite.id() + " used; " + invite.name() + " is now on the whitelist.");
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> mayHaveSession(UUID uuid) {
        return onServerThread(() -> {
            OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
            return allowed(Part.SIGNIN) && p.isWhitelisted() && !p.isBanned();
        });
    }

    private <T> CompletableFuture<T> onServerThread(java.util.concurrent.Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                try { f.complete(work.call()); } catch (Throwable t) { f.completeExceptionally(t); }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e); // The plugin is shutting down.
        }
        return f;
    }

    // --- keeping sessions in step with the whitelist ---

    /** Taken off the whitelist: every browser session for that player ends before the command finishes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWhitelistChange(WhitelistStateUpdateEvent event) {
        if (event.getStatus() != WhitelistStateUpdateEvent.WhitelistStatus.REMOVED) return;
        UUID id = event.getPlayerProfile().getId();
        if (id == null) return;
        try {
            int n = sessions.signOutEverywhere(id);
            if (n > 0) getLogger().info("Ended " + n + " browser session(s) for a player taken off the whitelist.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't end the sessions of a player taken off the whitelist", e);
        }
    }

    /** The whitelist turned off: nobody can prove they belong, so every session ends. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWhitelistToggle(WhitelistToggleEvent event) {
        whitelistOn = event.isEnabled();
        if (whitelistOn) return;
        try {
            int n = sessions.signOutWhere(uuid -> true);
            getLogger().warning("The whitelist was turned off, so invites and sign-in stopped and " + n + " browser session(s) ended.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't end browser sessions after the whitelist was turned off", e);
        }
    }

    // --- who is actually playing (owner, 2026-11-09: playing keeps a session open) ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), to = e.getTo();
        if (ActivityTracker.changedBlock(f.getBlockX(), f.getBlockY(), f.getBlockZ(), to.getBlockX(), to.getBlockY(), to.getBlockZ()))
            activity.saw(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) { activity.saw(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) { activity.saw(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { activity.saw(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent e) { activity.saw(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p) activity.saw(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p) activity.saw(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { activity.left(e.getPlayer().getUniqueId()); }

    /**
     * Ends sessions for anyone no longer whitelisted or now banned, extends those of players who are playing, and
     * clears out expired ones.
     */
    private void recheckSessions() {
        try {
            sessions.signOutWhere(uuid -> {
                OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
                return !whitelistOn || !p.isWhitelisted() || p.isBanned();
            });
            if (allowed(Part.SIGNIN)) {
                int n = sessions.extendActive(uuid -> Bukkit.getPlayer(uuid) != null && activity.active(uuid),
                        setup.settings().sessionLifetime());
                if (n > 0) getLogger().info("Kept " + n + " browser session(s) open for a day because the player is playing.");
            }
            sessions.sweep();
            links.removeExpired(Instant.now());
            once.sweep(Instant.now());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't check browser sessions", e);
        }
    }

    // --- /signin ---

    private void clearLinks() {
        try {
            links.removeAll();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't clear old sign-in links", e);
        }
    }

    private static boolean identitiesProven() {
        return Bukkit.getOnlineMode() || Boolean.getBoolean(TESTING_FLAG);
    }

    /** Where links point: Latchkey's own page when it's running, otherwise the older sign-in-page setting. */
    private String signInPage() {
        if (landing != null) return setup.settings().publicUrl().toString();
        return getConfig().getString("sign-in-page", "");
    }

    /**
     * The link is for whoever typed the command. The sender, not the executor: {@code /execute as <player>} changes the
     * executor, and must never make a link for someone else.
     */
    private void signIn(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Only a player in game can ask for a sign-in link.", NamedTextColor.RED));
            return;
        }
        if (sessions == null || !allowed(Part.SIGNIN)) {
            refuse(player, "Sign-in is off on this server. Whoever runs it can see why in the console.");
            return;
        }
        if (!player.isWhitelisted()) {
            refuse(player, "You aren't on this server's allowlist.");
            return;
        }
        String page = signInPage();
        if (!SignInLinks.usablePage(page)) {
            refuse(player, "Sign-in hasn't been set up on this server yet.");
            return;
        }
        String token;
        try {
            token = links.issue(player.getUniqueId(), player.getName(), Instant.now());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't save a sign-in link", e);
            refuse(player, "That didn't work. Tell whoever runs this server.");
            return;
        }
        if (token == null) {
            refuse(player, "You just asked for one. Try again in a few seconds.");
            return;
        }
        String link = SignInLinks.link(page.trim(), token);
        player.sendMessage(Component.text("Click here to sign in", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(Component.text("Works once, for two minutes. Don't share it."))));
        player.sendMessage(Component.text("It works once, for two minutes. Don't share it.", NamedTextColor.GRAY));
    }

    private static void refuse(Player player, String why) {
        player.sendMessage(Component.text(why, NamedTextColor.RED));
    }

}
