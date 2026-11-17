package dev.stats;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

public class StatsPlugin extends JavaPlugin implements Listener {

    static class C {
        final AtomicLong kills = new AtomicLong(), pvpDeaths = new AtomicLong(), deaths = new AtomicLong(),
                broken = new AtomicLong(), placed = new AtomicLong();
        final DoubleAdder dealt = new DoubleAdder(), received = new DoubleAdder();
        final Map<String, Long> mobs = new ConcurrentHashMap<>();
        volatile long lastTick = System.currentTimeMillis();
    }

    private final Map<UUID, C> data = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private String url, user, pass;

    record Snap(UUID id, byte[] inv, String invText, byte[] ec, String ecText) {}
    private final Map<UUID, Snap> pendingSnaps = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var c = getConfig();
        url = "jdbc:mariadb://" + c.getString("database.host") + ":" + c.getInt("database.port")
                + "/" + c.getString("database.name");
        user = c.getString("database.user");
        pass = c.getString("database.password");
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (ClassNotFoundException e) { getLogger().severe("MariaDB driver missing: " + e); }
        try (Connection t = conn()) {
            ensureTables(t);
            getLogger().info("Database connected.");
        } catch (SQLException e) {
            getLogger().severe("DB connection failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        long period = 20L * c.getInt("flush-seconds", 60);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushAll, period, period);
        long invPeriod = 20L * c.getInt("inventory-save-seconds", 300);
        getServer().getScheduler().runTaskTimer(this, () -> {
            List<Snap> list = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) list.add(snap(p));
            async(() -> {
                try (Connection cn = conn()) {
                    for (Snap sn : list) saveSnap(cn, sn);
                } catch (SQLException ex) { err(ex); }
            });
        }, invPeriod, invPeriod);
    }

    @Override
    public void onDisable() {
        flushAll();
        try (Connection cn = conn()) {
            for (Player p : getServer().getOnlinePlayers()) pendingSnaps.put(p.getUniqueId(), snap(p));
            for (Snap sn : pendingSnaps.values()) saveSnap(cn, sn);
        } catch (SQLException ex) { err(ex); }
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    private C c(UUID id) {
        return data.computeIfAbsent(id, k -> new C());
    }

    private void async(Runnable r) {
        getServer().getScheduler().runTaskAsynchronously(this, r);
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        names.put(id, p.getName());
        c(id).lastTick = System.currentTimeMillis();
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : null;
        async(() -> {
            try (Connection cn = conn()) {
                upsertPlayer(cn, id, p.getName());
                history(cn, id, "JOIN", ip);
            } catch (SQLException ex) { err(ex); }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String ip = e.getPlayer().getAddress() != null ? e.getPlayer().getAddress().getAddress().getHostAddress() : null;
        Snap sn = snap(e.getPlayer());
        pendingSnaps.put(id, sn);
        async(() -> {
            try (Connection cn = conn()) {
                saveSnap(cn, sn);
                pendingSnaps.remove(id, sn);
                flush(cn, id);
                history(cn, id, "LEAVE", ip);
                data.remove(id);
            } catch (SQLException ex) { err(ex); }
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        c(v.getUniqueId()).deaths.incrementAndGet();
        Player k = v.getKiller();
        if (k != null && !k.equals(v)) {
            c(k.getUniqueId()).kills.incrementAndGet();
            c(v.getUniqueId()).pvpDeaths.incrementAndGet();
        }
    }

    @EventHandler
    public void onMobKill(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        if (k != null) c(k.getUniqueId()).mobs.merge(e.getEntityType().name(), 1L, Long::sum);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { c(e.getPlayer().getUniqueId()).broken.incrementAndGet(); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { c(e.getPlayer().getUniqueId()).placed.incrementAndGet(); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player v) c(v.getUniqueId()).received.add(e.getFinalDamage());
        if (e instanceof EntityDamageByEntityEvent be) {
            var d = be.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Player s) d = s;
            if (d instanceof Player dp) c(dp.getUniqueId()).dealt.add(e.getFinalDamage());
        }
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String key = e.getAdvancement().getKey().toString();
        if (key.contains("recipes/")) return;
        UUID id = e.getPlayer().getUniqueId();
        async(() -> {
            try (Connection cn = conn();
                 PreparedStatement ps = cn.prepareStatement(
                         "INSERT IGNORE INTO advancements(uuid,advancement) VALUES(?,?)")) {
                ps.setString(1, id.toString());
                ps.setString(2, key);
                ps.executeUpdate();
            } catch (SQLException ex) { err(ex); }
        });
    }

    // ---------- Inventory ----------

    private Snap snap(Player p) {
        ItemStack[] inv = p.getInventory().getContents();
        ItemStack[] ec = p.getEnderChest().getContents();
        return new Snap(p.getUniqueId(), ItemStack.serializeItemsAsBytes(inv), text(inv),
                ItemStack.serializeItemsAsBytes(ec), text(ec));
    }

    private String text(ItemStack[] items) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack it : items)
            if (it != null && !it.getType().isAir()) m.merge(it.getType().name(), it.getAmount(), Integer::sum);
        StringJoiner j = new StringJoiner(", ");
        m.forEach((k, v) -> j.add(k + " x" + v));
        return j.toString();
    }

    private void saveSnap(Connection cn, Snap s) throws SQLException {
        exec(cn, "INSERT INTO player_inventory(uuid,inventory_data,inventory_text,enderchest_data,enderchest_text) VALUES(?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE inventory_data=VALUES(inventory_data), inventory_text=VALUES(inventory_text), "
                + "enderchest_data=VALUES(enderchest_data), enderchest_text=VALUES(enderchest_text)",
                s.id().toString(), s.inv(), s.invText(), s.ec(), s.ecText());
    }

    private void ensureTables(Connection cn) throws SQLException {
        try (Statement st = cn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS player_inventory ("
                    + "uuid CHAR(36) PRIMARY KEY, inventory_data LONGBLOB, inventory_text TEXT, "
                    + "enderchest_data LONGBLOB, enderchest_text TEXT, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        }
    }

    // ---------- DB ----------

    private void upsertPlayer(Connection cn, UUID id, String name) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(
                "INSERT INTO players(uuid,name) VALUES(?,?) ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=NOW()")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void history(Connection cn, UUID id, String type, String ip) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement("INSERT INTO join_history(uuid,event,ip) VALUES(?,?,?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, type);
            ps.setString(3, ip);
            ps.executeUpdate();
        }
    }

    private void flushAll() {
        try (Connection cn = conn()) {
            for (UUID id : new ArrayList<>(data.keySet())) flush(cn, id);
        } catch (SQLException ex) { err(ex); }
    }

    private void flush(Connection cn, UUID id) throws SQLException {
        C s = data.get(id);
        if (s == null) return;
        String u = id.toString();
        long now = System.currentTimeMillis();
        long secs = (now - s.lastTick) / 1000;
        s.lastTick = now;
        long k = s.kills.getAndSet(0), pd = s.pvpDeaths.getAndSet(0), d = s.deaths.getAndSet(0),
                b = s.broken.getAndSet(0), p = s.placed.getAndSet(0);
        double dd = s.dealt.sumThenReset(), dr = s.received.sumThenReset();

        if (names.containsKey(id)) upsertPlayer(cn, id, names.get(id));
        exec(cn, "INSERT INTO pvp_stats(uuid,kills,deaths) VALUES(?,?,?) ON DUPLICATE KEY UPDATE kills=kills+VALUES(kills), deaths=deaths+VALUES(deaths)", u, k, pd);
        exec(cn, "INSERT INTO death_stats(uuid,total_deaths) VALUES(?,?) ON DUPLICATE KEY UPDATE total_deaths=total_deaths+VALUES(total_deaths)", u, d);
        exec(cn, "INSERT INTO playtime(uuid,seconds) VALUES(?,?) ON DUPLICATE KEY UPDATE seconds=seconds+VALUES(seconds)", u, secs);
        exec(cn, "INSERT INTO block_stats(uuid,broken,placed) VALUES(?,?,?) ON DUPLICATE KEY UPDATE broken=broken+VALUES(broken), placed=placed+VALUES(placed)", u, b, p);
        exec(cn, "INSERT INTO damage_stats(uuid,dealt,received) VALUES(?,?,?) ON DUPLICATE KEY UPDATE dealt=dealt+VALUES(dealt), received=received+VALUES(received)", u, dd, dr);
        for (String mob : new ArrayList<>(s.mobs.keySet())) {
            Long n = s.mobs.remove(mob);
            if (n != null) exec(cn, "INSERT INTO mob_kills(uuid,mob_type,kills) VALUES(?,?,?) ON DUPLICATE KEY UPDATE kills=kills+VALUES(kills)", u, mob, n);
        }
    }

    private void exec(Connection cn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private void err(SQLException e) { getLogger().warning("SQL error: " + e.getMessage()); }
}
