package net.nehaverse.landpay;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public final class Landpay extends JavaPlugin implements Listener, CommandExecutor {

    // 経済
    private Economy econ;

    // SQLite
    private Connection conn;

    // ワンドの NBT キー
    private final String WAND_KEY = "landpay_wand";

    // 選択位置保持
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    // 購入待ちプレイヤー  <UUID, landName>
    private final Map<UUID, String> waitingConfirm = new HashMap<>();

    // prefix
    private String prefix;

    @Override
    public void onEnable() {
        // config
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        prefix = ChatColor.translateAlternateColorCodes('&',
                cfg.getString("messages.prefix", "&6[Landpay]&r "));

        // Vault
        if (!setupEconomy()) {
            getLogger().severe("Vault が見つからないため無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // SQLite
        try {
            openDatabase();
            prepareTables();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "SQLite 初期化失敗", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // commands
        String[] cmds = {
                "lwand", "sland", "pland", "landlist", "dland", "buyland", "prland"
        };
        CommandMap cm = getCommandMap();
        for (String c : cmds) {
            PluginCommand pc = getCommand(c);
            if (pc != null) pc.setExecutor(this);
        }

        // listener
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Landpay enable!");
    }

    @Override
    public void onDisable() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {
        }
        getLogger().info("Landpay disable.");
    }

    /* =========================================================
     * Command Handling
     * ========================================================= */
    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("player only");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "lwand" -> cmdLwand(player);
            case "sland" -> cmdSland(player, args);
            case "buyland" -> cmdBuyland(player, args);
            case "pland" -> cmdPland(player, args);
            case "landlist" -> cmdLandlist(player);
            case "dland" -> cmdDland(player, args);
            case "prland" -> cmdPrland(player, args);
            default -> {
            }
        }
        return true;
    }

    /* ----------------- /lwand --------------- */
    private void cmdLwand(Player p) {
        ItemStack wand = createWand();
        p.getInventory().addItem(wand);
        msg(p, "wand-given");
    }

    /* ----------------- /sland <price> <name> --------------- */
    private void cmdSland(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage("/sland <価格> <land名>");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            p.sendMessage("価格が不正です。");
            return;
        }
        String name = args[1];

        if (regionExists(name)) {
            msg(p, "region-exists");
            return;
        }
        Location l1 = pos1.get(p.getUniqueId());
        Location l2 = pos2.get(p.getUniqueId());
        if (l1 == null || l2 == null) {
            p.sendMessage("まず wand で範囲を選択してください。");
            return;
        }
        Region r = new Region(name, l1, l2, p.getName(), price, true, false);
        try {
            insertRegion(r);
            msg(p, "land-for-sale",
                    Map.of("name", name, "price", String.valueOf(price)));
        } catch (SQLException e) {
            p.sendMessage("DB 失敗: " + e.getMessage());
        }
    }

    /* ----------------- /buyland <name> --------------- */
    private void cmdBuyland(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage("/buyland <land名>");
            return;
        }
        String name = args[0];
        try {
            Region r = getRegion(name);
            if (r == null || !r.forSale) {
                msg(p, "region-not-found");
                return;
            }
            if (r.owner.equals(p.getName())) {
                p.sendMessage("自分の土地です。");
                return;
            }
            waitingConfirm.put(p.getUniqueId(), name);
            msg(p, "purchase-confirm", Map.of(
                    "name", r.name,
                    "price", String.valueOf(r.price)));
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* ----------------- /pland <land> <user> --------------- */
    private void cmdPland(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage("/pland <land名> <ユーザー>");
            return;
        }
        String land = args[0];
        String user = args[1];

        try {
            Region r = getRegion(land);
            if (r == null) {
                msg(p, "region-not-found");
                return;
            }
            if (!r.owner.equals(p.getName()) && !p.hasPermission("landpay.admin")) {
                msg(p, "not-owner");
                return;
            }
            grantPermission(r.name, user);
            p.sendMessage("許可しました。");
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* ----------------- /landlist --------------- */
    private void cmdLandlist(Player p) {
        try {
            List<Region> list = listForSale();
            if (list.isEmpty()) {
                p.sendMessage("販売中の土地はありません。");
                return;
            }
            for (Region r : list) {
                msg(p, "land-listed",
                        Map.of("name", r.name, "price", String.valueOf(r.price)), p);
            }
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* ----------------- /dland <name> --------------- */
    private void cmdDland(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage("/dland <land名>");
            return;
        }
        String name = args[0];
        try {
            Region r = getRegion(name);
            if (r == null) {
                msg(p, "region-not-found");
                return;
            }
            if (!r.owner.equals(p.getName()) && !p.hasPermission("landpay.admin")) {
                msg(p, "not-owner");
                return;
            }
            deleteRegion(name);
            msg(p, "land-deleted", Map.of("name", name), p);
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* ----------------- /prland <name> --------------- */
    private void cmdPrland(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage("/prland <保護名>");
            return;
        }
        String name = args[0];
        if (regionExists(name)) {
            msg(p, "region-exists");
            return;
        }
        Location l1 = pos1.get(p.getUniqueId());
        Location l2 = pos2.get(p.getUniqueId());
        if (l1 == null || l2 == null) {
            p.sendMessage("まず wand で範囲を選択してください。");
            return;
        }
        // 体積チェック
        int volume = Math.abs(l1.getBlockX() - l2.getBlockX()) *
                Math.abs(l1.getBlockY() - l2.getBlockY()) *
                Math.abs(l1.getBlockZ() - l2.getBlockZ());
        int max = getConfig().getInt("max-protect-volume", 100000);
        if (volume > max && !p.hasPermission("landpay.admin")) {
            p.sendMessage("体積が大きすぎます: " + volume + " / " + max);
            return;
        }
        Region r = new Region(name, l1, l2, p.getName(), 0, false, true);
        try {
            insertRegion(r);
            msg(p, "protect-created", Map.of("name", name), p);
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* =========================================================
     * Chat Listener for /buy confirm
     * ========================================================= */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!waitingConfirm.containsKey(id)) return;

        String land = waitingConfirm.get(id);
        if (e.getMessage().equalsIgnoreCase("yes")) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> completePurchase(e.getPlayer(), land));
            waitingConfirm.remove(id);
        } else if (e.getMessage().equalsIgnoreCase("no")) {
            e.setCancelled(true);
            waitingConfirm.remove(id);
            e.getPlayer().sendMessage(prefix + "キャンセルしました。");
        }
    }

    private void completePurchase(Player p, String land) {
        try {
            Region r = getRegion(land);
            if (r == null || !r.forSale) {
                msg(p, "region-not-found");
                return;
            }
            if (econ.getBalance(p) < r.price) {
                p.sendMessage("お金が足りません。");
                return;
            }
            // withdraw & pay previous owner
            econ.withdrawPlayer(p, r.price);
            OfflinePlayer prev = Bukkit.getOfflinePlayer(r.owner);
            econ.depositPlayer(prev, r.price);

            // update region
            setNewOwner(land, p.getName());
            msg(p, "purchased", Map.of("name", land));
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
        }
    }

    /* =========================================================
     * Wand Listener
     * ========================================================= */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || !isWand(item)) return;

        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null) return;

        Location loc = b.getLocation();
        if (e.getAction().isRightClick()) {
            pos1.put(p.getUniqueId(), loc);
            msg(p, "set-pos1", mapFromLoc(loc));
        } else if (e.getAction().isLeftClick()) {
            pos2.put(p.getUniqueId(), loc);
            msg(p, "set-pos2", mapFromLoc(loc));
        }
    }

    /* =========================================================
     * Block protection
     * ========================================================= */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (checkProtected(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (checkProtected(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    private boolean checkProtected(Player p, Block b) {
        try {
            Region r = getRegionAt(b.getLocation());
            if (r == null) return false;

            // op 無効化
            if (p.isOp() || p.hasPermission("landpay.admin")) return false;

            if (r.owner.equals(p.getName())) return false;
            if (hasPermission(r.name, p.getName())) return false;

            // forSale 中
            if (r.forSale) {
                msg(p, "block-deny", Map.of(
                        "name", r.name,
                        "owner", r.owner), p);
                return true;
            }

            // 保護 or 他人所有
            msg(p, "block-deny", Map.of(
                    "name", r.name,
                    "owner", r.owner), p);
            return true;
        } catch (SQLException e) {
            p.sendMessage("DB Error: " + e.getMessage());
            return true;
        }
    }

    /* =========================================================
     * Utility
     * ========================================================= */
    private Map<String, String> mapFromLoc(Location l) {
        return Map.of(
                "x", String.valueOf(l.getBlockX()),
                "y", String.valueOf(l.getBlockY()),
                "z", String.valueOf(l.getBlockZ())
        );
    }

    private void msg(Player p, String key) {
        msg(p, key, Collections.emptyMap(), p);
    }

    private void msg(Player p, String key, Map<String, String> rep) {
        msg(p, key, rep, p);
    }

    private void msg(CommandSender receiver, String key, Map<String, String> rep, Player colorizeBase) {
        String path = "messages." + key;
        String raw = getConfig().getString(path, "&c" + key);
        for (Map.Entry<String, String> en : rep.entrySet()) {
            raw = raw.replace("%" + en.getKey() + "%", en.getValue());
        }
        receiver.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + raw));
    }

    private ItemStack createWand() {
        Material mat = Material.matchMaterial(getConfig().getString("wand-material", "WOODEN_AXE"));
        ItemStack is = new ItemStack(mat == null ? Material.WOODEN_AXE : mat);
        ItemMeta im = is.getItemMeta();
        im.setDisplayName(ChatColor.GOLD + "Land Wand");
        im.addEnchant(Enchantment.UNBREAKING, 1, true);
        im.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        im.getPersistentDataContainer().set(new NamespacedKey(this, WAND_KEY),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        is.setItemMeta(im);
        return is;
    }

    private boolean isWand(ItemStack is) {
        if (is == null) return false;
        ItemMeta im = is.getItemMeta();
        if (im == null) return false;
        Byte b = im.getPersistentDataContainer().get(
                new NamespacedKey(this, WAND_KEY),
                org.bukkit.persistence.PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    /* =========================================================
     * Database
     * ========================================================= */
    private void openDatabase() throws SQLException {
        File dbFile = new File(getDataFolder(), "landpay.db");
        getDataFolder().mkdirs();
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        conn = DriverManager.getConnection(url);
    }

    private void prepareTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS regions(
                        name TEXT PRIMARY KEY,
                        world TEXT,
                        x1 INT, y1 INT, z1 INT,
                        x2 INT, y2 INT, z2 INT,
                        owner TEXT,
                        price REAL,
                        forSale INT,
                        protected INT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS permissions(
                        land TEXT,
                        user TEXT,
                        PRIMARY KEY (land, user)
                    )
                    """);
        }
    }

    private void insertRegion(Region r) throws SQLException {
        String sql = """
                INSERT INTO regions(name, world,x1,y1,z1,x2,y2,z2,owner,price,forSale,protected)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.name);
            ps.setString(2, r.world);
            ps.setInt(3, r.x1);
            ps.setInt(4, r.y1);
            ps.setInt(5, r.z1);
            ps.setInt(6, r.x2);
            ps.setInt(7, r.y2);
            ps.setInt(8, r.z2);
            ps.setString(9, r.owner);
            ps.setDouble(10, r.price);
            ps.setInt(11, r.forSale ? 1 : 0);
            ps.setInt(12, r.protectedRegion ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private boolean regionExists(String name) {
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT 1 FROM regions WHERE name=?")) {
            ps.setString(1, name);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    private Region getRegion(String name) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT * FROM regions WHERE name=?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return regionFromRs(rs);
        }
    }

    private List<Region> listForSale() throws SQLException {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM regions WHERE forSale=1");
            List<Region> list = new ArrayList<>();
            while (rs.next()) list.add(regionFromRs(rs));
            return list;
        }
    }

    private void deleteRegion(String name) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("DELETE FROM regions WHERE name=?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                     conn.prepareStatement("DELETE FROM permissions WHERE land=?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    private void setNewOwner(String name, String newOwner) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("UPDATE regions SET owner=?, forSale=0 WHERE name=?")) {
            ps.setString(1, newOwner);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void grantPermission(String land, String user) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("INSERT OR IGNORE INTO permissions(land,user) VALUES(?,?)")) {
            ps.setString(1, land);
            ps.setString(2, user);
            ps.executeUpdate();
        }
    }

    private boolean hasPermission(String land, String user) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT 1 FROM permissions WHERE land=? AND user=?")) {
            ps.setString(1, land);
            ps.setString(2, user);
            return ps.executeQuery().next();
        }
    }

    private Region getRegionAt(Location loc) throws SQLException {
        String sql = "SELECT * FROM regions WHERE world=? AND " +
                "((x1<=? AND x2>=?) OR (x1>=? AND x2<=?)) AND " +
                "((y1<=? AND y2>=?) OR (y1>=? AND y2<=?)) AND " +
                "((z1<=? AND z2>=?) OR (z1>=? AND z2<=?))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loc.getWorld().getName());
            ps.setInt(2, loc.getBlockX());
            ps.setInt(3, loc.getBlockX());
            ps.setInt(4, loc.getBlockX());
            ps.setInt(5, loc.getBlockX());

            ps.setInt(6, loc.getBlockY());
            ps.setInt(7, loc.getBlockY());
            ps.setInt(8, loc.getBlockY());
            ps.setInt(9, loc.getBlockY());

            ps.setInt(10, loc.getBlockZ());
            ps.setInt(11, loc.getBlockZ());
            ps.setInt(12, loc.getBlockZ());
            ps.setInt(13, loc.getBlockZ());

            ResultSet rs = ps.executeQuery();
            if (rs.next()) return regionFromRs(rs);
            return null;
        }
    }

    private Region regionFromRs(ResultSet rs) throws SQLException {
        return new Region(
                rs.getString("name"),
                new Location(Bukkit.getWorld(rs.getString("world")),
                        rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1")),
                new Location(Bukkit.getWorld(rs.getString("world")),
                        rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2")),
                rs.getString("owner"),
                rs.getDouble("price"),
                rs.getInt("forSale") == 1,
                rs.getInt("protected") == 1
        );
    }

    /* =========================================================
     * Economy
     * ========================================================= */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    /* =========================================================
     * Helper
     * ========================================================= */
    private CommandMap getCommandMap() {
        try {
            java.lang.reflect.Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            return (CommandMap) f.get(Bukkit.getServer());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch commandMap", e);
        }
    }

    /* =========================================================
     * Region POJO
     * ========================================================= */
    private record Region(
            String name,
            String world,
            int x1, int y1, int z1,
            int x2, int y2, int z2,
            String owner,
            double price,
            boolean forSale,
            boolean protectedRegion
    ) {
        Region(String name, Location l1, Location l2,
               String owner, double price,
               boolean forSale, boolean protectedRegion) {
            this(name,
                    l1.getWorld().getName(),
                    l1.getBlockX(), l1.getBlockY(), l1.getBlockZ(),
                    l2.getBlockX(), l2.getBlockY(), l2.getBlockZ(),
                    owner, price, forSale, protectedRegion);
        }
    }
}