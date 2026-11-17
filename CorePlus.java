package de.coreplus;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class CorePlus extends JavaPlugin implements Listener, TabCompleter {

    private static final String P = "§8[§bCore§8] §7";

    private static class Request {
        final UUID from; final boolean here; final long time;
        Request(UUID from, boolean here) { this.from = from; this.here = here; this.time = System.currentTimeMillis(); }
    }

    private static class RtpHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<UUID, Request> requests = new HashMap<>();
    private final Set<UUID> autoAccept = new HashSet<>();
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();
    private final Map<UUID, Location> warmupStart = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String c : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpauto", "home", "sethome",
                "delhome", "homes", "rtp", "rtpq", "spawn", "setspawn")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(this); pc.setTabCompleter(this); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CorePlus aktiviert.");
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("data.yml konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    private void msg(CommandSender s, String m) { s.sendMessage(P + m); }

    // ------------------------------------------------------------ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Nur fuer Spieler."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "tpa" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/tpa <Spieler>§7, §b/tpa accept§7, §b/tpa deny"); return true; }
                if (args[0].equalsIgnoreCase("accept")) accept(p);
                else if (args[0].equalsIgnoreCase("deny")) deny(p);
                else sendRequest(p, args[0], false);
            }
            case "tpahere" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/tpahere <Spieler>"); return true; }
                sendRequest(p, args[0], true);
            }
            case "tpaccept" -> accept(p);
            case "tpdeny" -> deny(p);
            case "tpauto" -> {
                if (autoAccept.remove(p.getUniqueId())) msg(p, "Auto-Accept §cdeaktiviert§7.");
                else { autoAccept.add(p.getUniqueId()); msg(p, "Auto-Accept §aaktiviert§7. Anfragen werden automatisch angenommen."); }
            }
            case "sethome" -> setHome(p, args.length > 0 ? args[0] : "home");
            case "home" -> home(p, args.length > 0 ? args[0] : null);
            case "delhome" -> {
                if (args.length == 0) { msg(p, "Benutzung: §b/delhome <name>"); return true; }
                String path = "homes." + p.getUniqueId() + "." + args[0].toLowerCase();
                if (!data.contains(path)) { msg(p, "§cDieses Home gibt es nicht."); return true; }
                data.set(path, null); saveData();
                msg(p, "Home §b" + args[0] + " §7geloescht.");
            }
            case "homes" -> {
                Set<String> h = homeNames(p);
                msg(p, "Homes §8(" + h.size() + "/" + fmt(maxHomes(p)) + ")§7: §b" + (h.isEmpty() ? "keine" : String.join("§7, §b", h)));
            }
            case "rtp" -> openRtpGui(p);
            case "rtpq" -> rtpQueue(p);
            case "setspawn" -> {
                if (!p.hasPermission("coreplus.setspawn") && !p.isOp()) { msg(p, "§cDazu hast du keine Rechte."); return true; }
                data.set("spawn", p.getLocation()); saveData();
                p.getWorld().setSpawnLocation(p.getLocation());
                msg(p, "§aSpawn gesetzt.");
            }
            case "spawn" -> {
                Location spawn = data.getLocation("spawn");
                if (spawn == null) spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                teleport(p, spawn, "§aDu wurdest zum Spawn teleportiert.");
            }
        }
        return true;
    }

    // ------------------------------------------------------------ TPA
    private void sendRequest(Player p, String targetName, boolean here) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) { msg(p, "§cSpieler nicht gefunden."); return; }
        if (t.equals(p)) { msg(p, "§cDu kannst dich nicht zu dir selbst teleportieren."); return; }
        Request r = new Request(p.getUniqueId(), here);
        if (autoAccept.contains(t.getUniqueId())) {
            msg(p, "§b" + t.getName() + " §7hat Auto-Accept aktiv.");
            execute(r, t);
            return;
        }
        requests.put(t.getUniqueId(), r);
        msg(p, "Anfrage an §b" + t.getName() + " §7gesendet.");
        msg(t, "§b" + p.getName() + " §7moechte " + (here ? "dass du dich zu ihm teleportierst" : "sich zu dir teleportieren") + ".");
        msg(t, "§a/tpa accept §7oder §c/tpa deny §8(" + getConfig().getInt("tpa-timeout-seconds", 60) + "s)");
    }

    private Request validRequest(Player t) {
        Request r = requests.get(t.getUniqueId());
        if (r == null) { msg(t, "§cDu hast keine offene Anfrage."); return null; }
        if (System.currentTimeMillis() - r.time > getConfig().getInt("tpa-timeout-seconds", 60) * 1000L) {
            requests.remove(t.getUniqueId());
            msg(t, "§cDie Anfrage ist abgelaufen."); return null;
        }
        return r;
    }

    private void accept(Player t) {
        Request r = validRequest(t);
        if (r == null) return;
        requests.remove(t.getUniqueId());
        execute(r, t);
    }

    private void execute(Request r, Player target) {
        Player from = Bukkit.getPlayer(r.from);
        if (from == null) { msg(target, "§cDer Spieler ist nicht mehr online."); return; }
        msg(target, "Anfrage angenommen.");
        if (r.here) teleport(target, from.getLocation(), "§aTeleportiert zu §b" + from.getName() + "§a.");
        else teleport(from, target.getLocation(), "§aTeleportiert zu §b" + target.getName() + "§a.");
    }

    private void deny(Player t) {
        Request r = validRequest(t);
        if (r == null) return;
        requests.remove(t.getUniqueId());
        msg(t, "Anfrage abgelehnt.");
        Player from = Bukkit.getPlayer(r.from);
        if (from != null) msg(from, "§b" + t.getName() + " §chat deine Anfrage abgelehnt.");
    }

    // ------------------------------------------------------------ Homes
    private int maxHomes(Player p) {
        if (p.hasPermission("coreplus.homes.unlimited")) return Integer.MAX_VALUE;
        int max = getConfig().getInt("homes.default", 3);
        var ranks = getConfig().getConfigurationSection("homes.ranks");
        if (ranks != null) for (String perm : ranks.getKeys(false))
            if (p.hasPermission(perm)) max = Math.max(max, ranks.getInt(perm));
        for (PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
            String s = pai.getPermission().toLowerCase();
            if (pai.getValue() && s.startsWith("coreplus.homes.")) {
                try { max = Math.max(max, Integer.parseInt(s.substring("coreplus.homes.".length()))); } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    private String fmt(int n) { return n == Integer.MAX_VALUE ? "∞" : String.valueOf(n); }

    private Set<String> homeNames(Player p) {
        var sec = data.getConfigurationSection("homes." + p.getUniqueId());
        return sec == null ? new TreeSet<>() : new TreeSet<>(sec.getKeys(false));
    }

    private void setHome(Player p, String name) {
        name = name.toLowerCase();
        if (!name.matches("[a-z0-9_\\-]{1,16}")) { msg(p, "§cUngueltiger Name (a-z, 0-9, _ - ; max 16 Zeichen)."); return; }
        Set<String> homes = homeNames(p);
        if (!homes.contains(name) && homes.size() >= maxHomes(p)) {
            msg(p, "§cDu hast dein Limit von §b" + fmt(maxHomes(p)) + " §cHomes erreicht. Hol dir einen hoeheren Rang!");
            return;
        }
        data.set("homes." + p.getUniqueId() + "." + name, p.getLocation());
        saveData();
        msg(p, "Home §b" + name + " §7gesetzt §8(" + homeNames(p).size() + "/" + fmt(maxHomes(p)) + ")");
    }

    private void home(Player p, String name) {
        Set<String> homes = homeNames(p);
        if (homes.isEmpty()) { msg(p, "§cDu hast noch kein Home. Nutze §b/sethome§c."); return; }
        if (name == null) {
            if (homes.size() == 1) name = homes.iterator().next();
            else if (homes.contains("home")) name = "home";
            else { msg(p, "Deine Homes: §b" + String.join("§7, §b", homes)); return; }
        }
        Location l = data.getLocation("homes." + p.getUniqueId() + "." + name.toLowerCase());
        if (l == null) { msg(p, "§cDieses Home gibt es nicht. Deine Homes: §b" + String.join(", ", homes)); return; }
        teleport(p, l, "§aTeleportiert zu Home §b" + name + "§a.");
    }

    // ------------------------------------------------------------ RTP GUI
    private void openRtpGui(Player p) {
        Inventory inv = Bukkit.createInventory(new RtpHolder(), 27, "§8Random Teleport");
        FileConfiguration c = getConfig();
        inv.setItem(11, item(Material.GRASS_BLOCK, "§aOverworld", List.of("§7Klicke, um zu einer", "§7zufaelligen Position zu gehen.")));
        boolean nl = c.getBoolean("rtp.nether-locked", true) && !p.hasPermission("coreplus.rtp.nether");
        boolean el = c.getBoolean("rtp.end-locked", true) && !p.hasPermission("coreplus.rtp.end");
        inv.setItem(13, nl ? item(Material.BARRIER, "§cNether §8(§4gesperrt§8)", List.of("§7Der Nether ist derzeit gesperrt."))
                : item(Material.NETHERRACK, "§cNether", List.of("§7Klicke, um zu teleportieren.")));
        inv.setItem(15, el ? item(Material.BARRIER, "§5End §8(§4gesperrt§8)", List.of("§7Das End ist derzeit gesperrt."))
                : item(Material.END_STONE, "§5End", List.of("§7Klicke, um zu teleportieren.")));
        p.openInventory(inv);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name); im.setLore(lore);
        it.setItemMeta(im);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RtpHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        FileConfiguration c = getConfig();
        switch (e.getSlot()) {
            case 11 -> { p.closeInventory(); rtp(p, c.getString("rtp.world", "world"), c.getInt("rtp.radius", 5000)); }
            case 13 -> {
                if (c.getBoolean("rtp.nether-locked", true) && !p.hasPermission("coreplus.rtp.nether")) { msg(p, "§cDer Nether ist gesperrt."); return; }
                p.closeInventory(); rtp(p, c.getString("rtp.nether-world", "world_nether"), c.getInt("rtp.nether-radius", 1000));
            }
            case 15 -> {
                if (c.getBoolean("rtp.end-locked", true) && !p.hasPermission("coreplus.rtp.end")) { msg(p, "§cDas End ist gesperrt."); return; }
                p.closeInventory(); rtp(p, c.getString("rtp.end-world", "world_the_end"), c.getInt("rtp.radius", 1000));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RtpHolder) e.setCancelled(true);
    }

    private void rtp(Player p, String worldName, int radius) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { msg(p, "§cWelt §b" + worldName + " §cexistiert nicht."); return; }
        Location l = randomLocation(w, radius);
        if (l == null) { msg(p, "§cKein sicherer Ort gefunden, versuche es nochmal."); return; }
        teleport(p, l, "§aDu wurdest zu einer zufaelligen Position teleportiert.");
    }

    private Location randomLocation(World w, int radius) {
        WorldBorder b = w.getWorldBorder();
        double r = Math.min(radius, b.getSize() / 2 - 10);
        Location c = b.getCenter();
        for (int i = 0; i < 30; i++) {
            int x = (int) (c.getX() + (random.nextDouble() * 2 - 1) * r);
            int z = (int) (c.getZ() + (random.nextDouble() * 2 - 1) * r);
            int y;
            if (w.getEnvironment() == World.Environment.NETHER) {
                y = -1;
                for (int yy = 100; yy > 32; yy--) {
                    Block ground = w.getBlockAt(x, yy - 1, z);
                    if (ground.getType().isSolid() && w.getBlockAt(x, yy, z).isEmpty() && w.getBlockAt(x, yy + 1, z).isEmpty()
                            && ground.getType() != Material.BEDROCK) { y = yy; break; }
                }
                if (y < 0) continue;
            } else {
                y = w.getHighestBlockYAt(x, z) + 1;
            }
            Block ground = w.getBlockAt(x, y - 1, z);
            Material m = ground.getType();
            if (!m.isSolid() || ground.isLiquid() || m == Material.CACTUS || m == Material.MAGMA_BLOCK
                    || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.FIRE) continue;
            return new Location(w, x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
        }
        return null;
    }

    // ------------------------------------------------------------ RTPQ
    private void rtpQueue(Player p) {
        UUID id = p.getUniqueId();
        if (queue.remove(id)) { msg(p, "Du hast die Queue §cverlassen§7."); return; }
        queue.add(id);
        msg(p, "Du bist in der §bRTP-Queue§7. Warte auf einen anderen Spieler... §8(/rtpq zum Verlassen)");
        if (queue.size() >= 2) {
            Iterator<UUID> it = queue.iterator();
            Player a = Bukkit.getPlayer(it.next()); it.remove();
            Player b = Bukkit.getPlayer(it.next()); it.remove();
            if (a == null || b == null) { if (a != null) queue.add(a.getUniqueId()); if (b != null) queue.add(b.getUniqueId()); return; }
            World w = Bukkit.getWorld(getConfig().getString("rtp.world", "world"));
            Location l = w == null ? null : randomLocation(w, getConfig().getInt("rtp.radius", 5000));
            if (l == null) { msg(a, "§cKein sicherer Ort gefunden."); msg(b, "§cKein sicherer Ort gefunden."); return; }
            Location l2 = l.clone().add(1, 0, 0);
            msg(a, "Partner gefunden: §b" + b.getName());
            msg(b, "Partner gefunden: §b" + a.getName());
            teleport(a, l, "§aDu wurdest mit §b" + b.getName() + " §ateleportiert.");
            teleport(b, l2, "§aDu wurdest mit §b" + a.getName() + " §ateleportiert.");
        }
    }

    // ------------------------------------------------------------ Teleport mit Delay
    private void teleport(Player p, Location dest, String doneMsg) {
        int delay = getConfig().getInt("teleport-delay", 3);
        cancelWarmup(p.getUniqueId());
        if (delay <= 0 || p.hasPermission("coreplus.bypass")) {
            p.teleport(dest); msg(p, doneMsg); return;
        }
        msg(p, "Teleport in §b" + delay + "s§7 - nicht bewegen!");
        warmupStart.put(p.getUniqueId(), p.getLocation());
        warmups.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(this, () -> {
            warmups.remove(p.getUniqueId()); warmupStart.remove(p.getUniqueId());
            if (p.isOnline()) { p.teleport(dest); msg(p, doneMsg); }
        }, delay * 20L));
    }

    private void cancelWarmup(UUID id) {
        BukkitTask t = warmups.remove(id);
        if (t != null) t.cancel();
        warmupStart.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location start = warmupStart.get(e.getPlayer().getUniqueId());
        if (start == null || e.getTo() == null) return;
        if (start.getBlockX() != e.getTo().getBlockX() || start.getBlockY() != e.getTo().getBlockY()
                || start.getBlockZ() != e.getTo().getBlockZ()) {
            cancelWarmup(e.getPlayer().getUniqueId());
            msg(e.getPlayer(), "§cTeleport abgebrochen, du hast dich bewegt.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        queue.remove(id); requests.remove(id); cancelWarmup(id);
    }

    // ------------------------------------------------------------ Tab-Complete
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (!(s instanceof Player p) || args.length != 1) return List.of();
        String a = args[0].toLowerCase();
        String n = cmd.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (n.equals("tpa") || n.equals("tpahere")) {
            if (n.equals("tpa")) { out.add("accept"); out.add("deny"); }
            Bukkit.getOnlinePlayers().forEach(o -> { if (!o.equals(p)) out.add(o.getName()); });
        } else if (n.equals("home") || n.equals("delhome")) out.addAll(homeNames(p));
        return out.stream().filter(x -> x.toLowerCase().startsWith(a)).collect(Collectors.toList());
    }
}
