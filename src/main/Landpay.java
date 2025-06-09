package net.nehaverse.landpay;

import net.milkbowl.vault.economy.Economy;
import net.md_5.bungee.api.chat.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
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

    /* =========================================================
     * フィールド
     * ========================================================= */
    private Economy econ;
    private Connection conn;

    private final String WAND_KEY = "landpay_wand";

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, String> waitingConfirm = new HashMap<>();

    private String prefix;

    /* =========================================================
     * onEnable / onDisable
     * ========================================================= */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        prefix = ChatColor.translateAlternateColorCodes('&',
                cfg.getString("messages.prefix", "&6[Landpay]&r "));

        if (!setupEconomy()) {
            getLogger().severe("Vault が見つかりません。無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try { openDatabase(); prepareTables(); }
        catch (SQLException e) {
            getLogger().log(Level.SEVERE, "SQLite 初期化失敗", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        for (String c : List.of("lwand","sland","buyland",
                "landconfirm","landcancel","pland","landlist","dland","prland"))
            Objects.requireNonNull(getCommand(c)).setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Landpay enable!");
    }

    @Override
    public void onDisable() {
        try { if (conn!=null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
        getLogger().info("Landpay disable.");
    }

    /* =========================================================
     * Command
     * ========================================================= */
    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("player only"); return true; }

        switch (command.getName().toLowerCase()) {
            case "lwand"       -> cmdLwand(p);
            case "sland"       -> cmdSland(p, args);
            case "buyland"     -> cmdBuyland(p, args);
            case "landconfirm" -> cmdLandconfirm(p, args);
            case "landcancel"  -> cmdLandcancel(p);
            case "pland"       -> cmdPland(p, args);
            case "landlist"    -> cmdLandlist(p);
            case "dland"       -> cmdDland(p, args);
            case "prland"      -> cmdPrland(p, args);
        }
        return true;
    }

    /* ---------------- /lwand ---------------- */
    private void cmdLwand(Player p) {
        p.getInventory().addItem(createWand());
        msg(p, "wand-given");
    }

    /* ---------------- /sland 価格 name ------ */
    private void cmdSland(Player p, String[] a) {
        if (a.length<2){p.sendMessage("/sland <価格> <land名>");return;}
        double price; try{price=Double.parseDouble(a[0]);}
        catch(NumberFormatException e){p.sendMessage("価格が不正");return;}
        String name=a[1];
        if (regionExists(name)){msg(p,"region-exists");return;}

        Location l1=pos1.get(p.getUniqueId()), l2=pos2.get(p.getUniqueId());
        if(l1==null||l2==null){p.sendMessage("Wand で範囲選択して下さい");return;}

        Region r=new Region(name,l1,l2,p.getName(),price,true,false);
        try{insertRegion(r); msg(p,"land-for-sale",Map.of("name",name,"price",String.valueOf(price)));}
        catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* ---------------- /buyland name --------- */
    private void cmdBuyland(Player p,String[] a){
        if(a.length<1){p.sendMessage("/buyland <land名>");return;}
        try{
            Region r=getRegion(a[0]);
            if(r==null||!r.forSale){msg(p,"region-not-found");return;}
            if(r.owner.equals(p.getName())){p.sendMessage("自分の土地です");return;}
            waitingConfirm.put(p.getUniqueId(),r.name);
            sendConfirmMessage(p,r);
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* ---------------- /landconfirm ---------- */
    private void cmdLandconfirm(Player p,String[] a){
        String exp=waitingConfirm.get(p.getUniqueId());
        if(exp==null){p.sendMessage("購入待ちがありません");return;}
        if(a.length>=1&&!exp.equals(a[0])){p.sendMessage("確認待ちは "+exp+" です");return;}
        waitingConfirm.remove(p.getUniqueId());
        completePurchase(p,exp);
    }

    /* ---------------- /landcancel ----------- */
    private void cmdLandcancel(Player p){
        if(waitingConfirm.remove(p.getUniqueId())!=null)
            p.sendMessage(prefix+"キャンセルしました");
        else p.sendMessage("購入待ちがありません");
    }

    /* ---------------- /pland land user ------ */
    private void cmdPland(Player p,String[] a){
        if(a.length<2){p.sendMessage("/pland <land> <user>");return;}
        try{
            Region r=getRegion(a[0]);
            if(r==null){msg(p,"region-not-found");return;}
            if(!r.owner.equals(p.getName())&&!p.hasPermission("landpay.admin")){msg(p,"not-owner");return;}
            grantPermission(r.name,a[1]);
            p.sendMessage(prefix+"許可しました");
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* ---------------- /landlist ------------- */
    private void cmdLandlist(Player p){
        try{
            List<Region> list=listForSale();
            if(list.isEmpty()){p.sendMessage("販売中の土地はありません");return;}
            for(Region r:list) msg(p,"land-listed",Map.of("name",r.name,"price",String.valueOf(r.price)),p);
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* ---------------- /dland name ----------- */
    private void cmdDland(Player p,String[] a){
        if(a.length<1){p.sendMessage("/dland <land名>");return;}
        try{
            Region r=getRegion(a[0]);
            if(r==null){msg(p,"region-not-found");return;}
            if(!r.owner.equals(p.getName())&&!p.hasPermission("landpay.admin")){msg(p,"not-owner");return;}
            deleteRegion(r.name);
            msg(p,"land-deleted",Map.of("name",r.name),p);
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* ---------------- /prland --------------- */
    private void cmdPrland(Player p,String[] a){
        if(a.length<1){p.sendMessage("/prland <保護名>");return;}
        String name=a[0]; if(regionExists(name)){msg(p,"region-exists");return;}
        Location l1=pos1.get(p.getUniqueId()),l2=pos2.get(p.getUniqueId());
        if(l1==null||l2==null){p.sendMessage("Wand で範囲選択を");return;}
        int vol=Math.abs(l1.getBlockX()-l2.getBlockX())*
                Math.abs(l1.getBlockY()-l2.getBlockY())*
                Math.abs(l1.getBlockZ()-l2.getBlockZ());
        int max=getConfig().getInt("max-protect-volume",100000);
        if(vol>max&&!p.hasPermission("landpay.admin")){
            p.sendMessage("体積が大きすぎます "+vol+"/"+max);return;}
        Region r=new Region(name,l1,l2,p.getName(),0,false,true);
        try{insertRegion(r);msg(p,"protect-created",Map.of("name",name),p);}
        catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* =========================================================
     * インタラクション（Wand 選択 & ブロック破壊抑止）
     * ========================================================= */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e){
        ItemStack is=e.getItem();
        if(is==null||!isWand(is)) return;

        // Wand ではブロック操作させない
        e.setCancelled(true);

        Player p=e.getPlayer();
        Block b=e.getClickedBlock();
        if(b==null) return;
        Location loc=b.getLocation();

        if(e.getAction().isRightClick()){
            pos1.put(p.getUniqueId(),loc);
            msg(p,"set-pos1",mapLoc(loc));
        }else if(e.getAction().isLeftClick()){
            pos2.put(p.getUniqueId(),loc);
            msg(p,"set-pos2",mapLoc(loc));
        }
    }

    /* =========================================================
     * Block 保護チェック & 自動購入案内
     * ========================================================= */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent e){
        if(checkProtected(e.getPlayer(),e.getBlock())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e){
        if(checkProtected(e.getPlayer(),e.getBlock())) e.setCancelled(true);
    }

    private boolean checkProtected(Player p, Block b){
        try{
            Region r=getRegionAt(b.getLocation());
            if(r==null) return false;

            if(p.isOp()||p.hasPermission("landpay.admin")) return false;
            if(r.owner.equals(p.getName())||hasPermission(r.name,p.getName())) return false;

            if(r.forSale){
                // 自動購入案内
                waitingConfirm.put(p.getUniqueId(),r.name);
                sendConfirmMessage(p,r);
            }
            msg(p,"block-deny",Map.of("name",r.name,"owner",r.owner),p);
            return true;
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());return true;}
    }

    /* =========================================================
     * 購入確認 (クリック / yes/no)
     * ========================================================= */
    private void sendConfirmMessage(Player p, Region r){
        TextComponent base=new TextComponent(prefix+ChatColor.YELLOW+r.name+
                ChatColor.GRAY+" を "+ChatColor.AQUA+r.price+
                ChatColor.GRAY+" で購入しますか? ");

        TextComponent yes=new TextComponent(ChatColor.GREEN+"[はい]");
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/landconfirm "+r.name));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("クリックで購入").create()));

        TextComponent sp=new TextComponent(" ");
        TextComponent no=new TextComponent(ChatColor.RED+"[いいえ]");
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/landcancel"));
        no.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("キャンセル").create()));

        base.addExtra(yes); base.addExtra(sp); base.addExtra(no);
        p.spigot().sendMessage(base);
    }

    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=false)
    public void onChatLowest(AsyncPlayerChatEvent e){
        UUID id=e.getPlayer().getUniqueId();
        if(!waitingConfirm.containsKey(id)) return;

        String m=e.getMessage().trim().toLowerCase();
        if(!m.equals("yes")&&!m.equals("no")) return;

        e.setCancelled(true); // 公開チャットに流さない
        Bukkit.getScheduler().runTask(this,()->{
            if(m.equals("yes")) completePurchase(e.getPlayer(),waitingConfirm.remove(id));
            else                 waitingConfirm.remove(id);
        });
    }

    private void completePurchase(Player p,String land){
        try{
            Region r=getRegion(land);
            if(r==null||!r.forSale){msg(p,"region-not-found");return;}
            if(econ.getBalance(p)<r.price){p.sendMessage("お金が足りません");return;}

            econ.withdrawPlayer(p,r.price);
            econ.depositPlayer(Bukkit.getOfflinePlayer(r.owner),r.price);
            setNewOwner(land,p.getName());
            msg(p,"purchased",Map.of("name",land),p);
        }catch(SQLException e){p.sendMessage("DB Error:"+e.getMessage());}
    }

    /* =========================================================
     * Utility
     * ========================================================= */
    private Map<String,String> mapLoc(Location l){
        return Map.of("x",String.valueOf(l.getBlockX()),
                "y",String.valueOf(l.getBlockY()),
                "z",String.valueOf(l.getBlockZ()));
    }

    private void msg(Player p,String key){msg(p,key,Map.of(),p);}
    private void msg(Player p,String key,Map<String,String>rep){msg(p,key,rep,p);}
    private void msg(CommandSender to,String key,Map<String,String>rep,Player base){
        String raw=getConfig().getString("messages."+key,"&c"+key);
        for(var e:rep.entrySet()) raw=raw.replace("%"+e.getKey()+"%",e.getValue());
        to.sendMessage(ChatColor.translateAlternateColorCodes('&',prefix+raw));
    }

    private ItemStack createWand(){
        Material mat=Material.matchMaterial(getConfig().getString("wand-material","BLAZE_ROD"));
        ItemStack is=new ItemStack(mat==null?Material.BLAZE_ROD:mat);
        ItemMeta im=is.getItemMeta();
        im.setDisplayName(ChatColor.GOLD+"Land Wand");
        im.addEnchant(Enchantment.UNBREAKING,1,true);
        im.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        im.getPersistentDataContainer().set(new NamespacedKey(this,WAND_KEY),
                org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
        is.setItemMeta(im); return is;
    }
    private boolean isWand(ItemStack is){
        if(is==null) return false;
        ItemMeta im=is.getItemMeta(); if(im==null) return false;
        Byte b=im.getPersistentDataContainer().get(
                new NamespacedKey(this,WAND_KEY),
                org.bukkit.persistence.PersistentDataType.BYTE);
        return b!=null&&b==1;
    }

    /* =========================================================
     * DB
     * ========================================================= */
    private void openDatabase() throws SQLException{
        File db=new File(getDataFolder(),"landpay.db");getDataFolder().mkdirs();
        conn=DriverManager.getConnection("jdbc:sqlite:"+db.getAbsolutePath());
    }
    private void prepareTables() throws SQLException{
        try(Statement st=conn.createStatement()){
            st.executeUpdate("""
               CREATE TABLE IF NOT EXISTS regions(
                 name TEXT PRIMARY KEY,
                 world TEXT,
                 x1 INT,y1 INT,z1 INT,
                 x2 INT,y2 INT,z2 INT,
                 owner TEXT,
                 price REAL,
                 forSale INT,
                 protected INT)""");
            st.executeUpdate("""
               CREATE TABLE IF NOT EXISTS permissions(
                 land TEXT,
                 user TEXT,
                 PRIMARY KEY(land,user))""");
        }
    }

    private void insertRegion(Region r)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement(
                "INSERT INTO regions VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")){
            ps.setString(1,r.name); ps.setString(2,r.world);
            ps.setInt(3,r.x1);ps.setInt(4,r.y1);ps.setInt(5,r.z1);
            ps.setInt(6,r.x2);ps.setInt(7,r.y2);ps.setInt(8,r.z2);
            ps.setString(9,r.owner);ps.setDouble(10,r.price);
            ps.setInt(11,r.forSale?1:0); ps.setInt(12,r.protectedRegion?1:0);
            ps.executeUpdate();
        }
    }
    private boolean regionExists(String name){
        try(PreparedStatement ps=conn.prepareStatement("SELECT 1 FROM regions WHERE name=?")){
            ps.setString(1,name); return ps.executeQuery().next();
        }catch(SQLException e){return false;}
    }
    private Region getRegion(String name)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement("SELECT * FROM regions WHERE name=?")){
            ps.setString(1,name); var rs=ps.executeQuery(); if(!rs.next()) return null;
            return regionFromRs(rs);
        }
    }
    private List<Region> listForSale()throws SQLException{
        try(Statement st=conn.createStatement()){
            var rs=st.executeQuery("SELECT * FROM regions WHERE forSale=1");
            List<Region> l=new ArrayList<>(); while(rs.next())l.add(regionFromRs(rs)); return l;
        }
    }
    private void deleteRegion(String name)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement("DELETE FROM regions WHERE name=?")){
            ps.setString(1,name);ps.executeUpdate();}
        try(PreparedStatement ps=conn.prepareStatement("DELETE FROM permissions WHERE land=?")){
            ps.setString(1,name); ps.executeUpdate();}
    }
    private void setNewOwner(String land,String owner)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement(
                "UPDATE regions SET owner=?,forSale=0 WHERE name=?")){
            ps.setString(1,owner); ps.setString(2,land); ps.executeUpdate();}
    }
    private void grantPermission(String land,String user)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement(
                "INSERT OR IGNORE INTO permissions(land,user) VALUES(?,?)")){
            ps.setString(1,land); ps.setString(2,user); ps.executeUpdate();}
    }
    private boolean hasPermission(String land,String user)throws SQLException{
        try(PreparedStatement ps=conn.prepareStatement(
                "SELECT 1 FROM permissions WHERE land=? AND user=?")){
            ps.setString(1,land); ps.setString(2,user); return ps.executeQuery().next();}
    }
    private Region getRegionAt(Location loc)throws SQLException{
        String s="SELECT * FROM regions WHERE world=? AND "+
                "((x1<=? AND x2>=?) OR (x1>=? AND x2<=?)) AND "+
                "((y1<=? AND y2>=?) OR (y1>=? AND y2<=?)) AND "+
                "((z1<=? AND z2>=?) OR (z1>=? AND z2<=?))";
        try(PreparedStatement ps=conn.prepareStatement(s)){
            ps.setString(1,loc.getWorld().getName());
            for(int i=0;i<4;i++) ps.setInt(2+i,loc.getBlockX());
            for(int i=0;i<4;i++) ps.setInt(6+i,loc.getBlockY());
            for(int i=0;i<4;i++) ps.setInt(10+i,loc.getBlockZ());
            var rs=ps.executeQuery(); if(rs.next()) return regionFromRs(rs); return null;}
    }
    private Region regionFromRs(ResultSet rs)throws SQLException{
        return new Region(rs.getString("name"),rs.getString("world"),
                rs.getInt("x1"),rs.getInt("y1"),rs.getInt("z1"),
                rs.getInt("x2"),rs.getInt("y2"),rs.getInt("z2"),
                rs.getString("owner"),rs.getDouble("price"),
                rs.getInt("forSale")==1,rs.getInt("protected")==1);
    }

    /* =========================================================
     * Economy
     * ========================================================= */
    private boolean setupEconomy(){
        if(getServer().getPluginManager().getPlugin("Vault")==null) return false;
        RegisteredServiceProvider<Economy> rsp=
                getServer().getServicesManager().getRegistration(Economy.class);
        if(rsp==null) return false; econ=rsp.getProvider(); return econ!=null;
    }

    /* =========================================================
     * Region record
     * ========================================================= */
    private record Region(
            String name,String world,int x1,int y1,int z1,int x2,int y2,int z2,
            String owner,double price,boolean forSale,boolean protectedRegion){
        Region(String name,Location l1,Location l2,String owner,double price,
               boolean forSale,boolean protectedRegion){
            this(name,l1.getWorld().getName(),
                    l1.getBlockX(),l1.getBlockY(),l1.getBlockZ(),
                    l2.getBlockX(),l2.getBlockY(),l2.getBlockZ(),
                    owner,price,forSale,protectedRegion);}
    }
}