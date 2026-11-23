package mc.bastion.combat;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.block.Action;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BastionMCCombat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private final Map<UUID,Long> tagged = new HashMap<>();
    private final Map<UUID,Long> pearls = new HashMap<>();
    private final Map<UUID,Long> fallSafe = new HashMap<>();
    private final Map<UUID,Long> lastWarnings = new HashMap<>();
    private final Map<UUID,Long> endNotice = new HashMap<>();
    private final Map<String,Area> arenas = new LinkedHashMap<>();
    private FileConfiguration messages, arenaYaml;
    private File arenaFile;

    @Override public void onEnable() {
        saveDefaultConfig();
        File messageFile = new File(getDataFolder(), "messages.yml");
        if (!messageFile.exists()) saveResource("messages.yml", false);
        reloadExtra();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("combat")).setExecutor(this);
        Objects.requireNonNull(getCommand("combat")).setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        if (Bukkit.getPluginManager().isPluginEnabled("BastionMCProtections"))
            getLogger().warning("BastionMCProtections detectado: integración nativa pendiente de su API/código. El plugin no puede verificar automáticamente bases dinámicas ni bloquear su flag PvP todavía.");
    }
    @Override public void onDisable() { tagged.clear(); pearls.clear(); endNotice.clear(); }
    private void reloadExtra() {
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        arenaFile = new File(getDataFolder(), "arenas.yml");
        arenaYaml = YamlConfiguration.loadConfiguration(arenaFile);
        arenas.clear();
        ConfigurationSection section = arenaYaml.getConfigurationSection("arenas");
        if (section != null) for (String id: section.getKeys(false)) {
            String base = "arenas."+id;
            String world = arenaYaml.getString(base+".world");
            if (world==null) continue;
            Area a = new Area(world);
            a.first = readPoint(base+".first"); a.second = readPoint(base+".second");
            arenas.put(id, a);
        }
    }
    private static final class Point {
        final int x,y,z;
        Point(int x,int y,int z) {this.x=x;this.y=y;this.z=z;}
    }
    private static final class Area {
        final String world;
        Point first,second;
        Area(String world) {this.world=world;}
        boolean contains(Location l) {
            if (first==null || second==null || l.getWorld()==null || !world.equals(l.getWorld().getName())) return false;
            return l.getBlockX()>=Math.min(first.x,second.x) && l.getBlockX()<=Math.max(first.x,second.x)
                && l.getBlockY()>=Math.min(first.y,second.y) && l.getBlockY()<=Math.max(first.y,second.y)
                && l.getBlockZ()>=Math.min(first.z,second.z) && l.getBlockZ()<=Math.max(first.z,second.z);
        }
    }
    private Point readPoint(String path) {
        if (!arenaYaml.isConfigurationSection(path)) return null;
        return new Point(arenaYaml.getInt(path+".x"),arenaYaml.getInt(path+".y"),arenaYaml.getInt(path+".z"));
    }
    private void saveArenas() {
        arenaYaml.set("arenas",null);
        for (var entry:arenas.entrySet()) {
            String base="arenas."+entry.getKey(); Area a=entry.getValue();
            arenaYaml.set(base+".world",a.world);
            if (a.first!=null) savePoint(base+".first",a.first);
            if (a.second!=null) savePoint(base+".second",a.second);
        }
        try { arenaYaml.save(arenaFile); } catch(IOException e) {getLogger().severe("Error guardando arenas.yml: "+e.getMessage());}
    }
    private void savePoint(String path,Point p) {
        arenaYaml.set(path+".x",p.x);arenaYaml.set(path+".y",p.y);arenaYaml.set(path+".z",p.z);
    }
    private long now() {return System.currentTimeMillis();}
    private int duration() {return Math.max(1,getConfig().getInt("combat.seconds",20));}
    private int seconds(Player p) {
        long end=tagged.getOrDefault(p.getUniqueId(),0L);
        return end>now()?(int)Math.ceil((end-now())/1000.0):0;
    }
    private boolean active(Player p) {return seconds(p)>0;}
    private boolean enabledWorld(World w) {
        if (w==null) return false;
        List<String> enabled=getConfig().getStringList("combat.enabled-worlds");
        return (enabled.isEmpty() || enabled.contains(w.getName())) && !getConfig().getStringList("combat.disabled-worlds").contains(w.getName());
    }
    private boolean bypass(Player p) {return p.hasPermission("bastioncombat.bypass");}
    private void tag(Player p) {
        if (bypass(p) || !enabledWorld(p.getWorld()) || p.isDead()) return;
        boolean first=!active(p);
        tagged.put(p.getUniqueId(),now()+duration()*1000L);
        endNotice.remove(p.getUniqueId());
        if (first) {msg(p,"started",Map.of("seconds",String.valueOf(duration()))); enforceFlight(p);}
    }
    private void enforceFlight(Player p) {
        if (!getConfig().getBoolean("combat.anti-fly.enabled",true) || p.getGameMode()==GameMode.CREATIVE || p.getGameMode()==GameMode.SPECTATOR) return;
        if (p.isFlying()) {
            fallSafe.put(p.getUniqueId(),now()+1000L*Math.max(1,getConfig().getInt("combat.anti-fly.protect-fall-seconds",4)));
            p.setFlying(false);
        }
        p.setAllowFlight(false);
    }
    private Player source(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile projectile && getConfig().getBoolean("combat.tag-projectiles",true)) {
            ProjectileSource shooter=projectile.getShooter();
            if (shooter instanceof Player p) return p;
        }
        if (e instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        return null;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || e.getFinalDamage()<=0) return;
        Player attacker=source(e.getDamager());
        if (attacker==null || attacker.equals(victim)) return;
        if (!enabledWorld(victim.getWorld())) return;
        tag(attacker); tag(victim);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        Player p=e.getPlayer(); if (!active(p)) return;
        String[] parts=e.getMessage().trim().replaceFirst("^/","").toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length==0 || parts[0].isEmpty()) return;
        String command=parts[0];
        if (command.contains(":")) command=command.substring(command.lastIndexOf(':')+1);
        for (String entry:getConfig().getStringList("commands.blocked")) {
            String[] blocked=entry.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (blocked.length==0 || !command.equals(blocked[0])) continue;
            boolean match=true;
            for(int i=1;i<blocked.length;i++) if (i>=parts.length || !blocked[i].equals(parts[i])) {match=false;break;}
            if (match) {e.setCancelled(true);warning(p,"blocked-command");return;}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!active(e.getPlayer()) || e.getTo()==null) return;
        boolean pearl=e.getCause()==PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        if ((!pearl && getConfig().getBoolean("combat.block-teleports",true)) || forbiddenTransition(e.getPlayer(),e.getFrom(),e.getTo())) {
            e.setCancelled(true);warning(e.getPlayer(),"blocked-teleport");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        if (!active(e.getPlayer()) || e.getTo()==null) return;
        if (e.getFrom().getBlockX()==e.getTo().getBlockX() && e.getFrom().getBlockY()==e.getTo().getBlockY() && e.getFrom().getBlockZ()==e.getTo().getBlockZ()) return;
        if (forbiddenTransition(e.getPlayer(),e.getFrom(),e.getTo())) {
            e.setTo(e.getFrom());warning(e.getPlayer(),"blocked-safe");
        }
    }
    private boolean forbiddenTransition(Player player,Location from,Location to) {
        if (getConfig().getBoolean("combat.block-arena-exits",true))
            for (Area a: arenas.values()) if (a.contains(from) && !a.contains(to)) {warning(player,"blocked-arena");return true;}
        if (getConfig().getBoolean("combat.block-safe-zones",true) && !safe(from) && safe(to)) return true;
        return false;
    }
    private boolean safe(Location l) {
        for (Map<?,?> raw:getConfig().getMapList("protected-zones")) {
            try {
                if (!String.valueOf(raw.get("world")).equals(l.getWorld().getName())) continue;
                if (!(raw.get("min") instanceof Map<?,?> min) || !(raw.get("max") instanceof Map<?,?> max)) continue;
                if (coordinate(l.getBlockX(),min,max,"x") && coordinate(l.getBlockY(),min,max,"y") && coordinate(l.getBlockZ(),min,max,"z")) return true;
            } catch (RuntimeException ignored) { }
        }
        return WorldGuardBridge.isSafe(l,getConfig());
    }
    private boolean coordinate(int value,Map<?,?> min,Map<?,?> max,String key) {
        double a=Double.parseDouble(String.valueOf(min.get(key))),b=Double.parseDouble(String.valueOf(max.get(key)));
        return value>=Math.min(a,b) && value<=Math.max(a,b);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPearl(PlayerInteractEvent e) {
        if (e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND && e.getHand()!=org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        if (e.getAction()!=Action.RIGHT_CLICK_AIR && e.getAction()!=Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item=e.getItem();
        if (item==null || item.getType()!=Material.ENDER_PEARL || !getConfig().getBoolean("ender-pearls.enabled",true)) return;
        Player p=e.getPlayer();
        int cooldown=pearlCooldown(p.getLocation());
        long current=now(),expires=pearls.getOrDefault(p.getUniqueId(),0L);
        if (expires>current) {
            e.setCancelled(true);
            warning(p,"pearl-cooldown",Map.of("seconds",String.valueOf((int)Math.ceil((expires-current)/1000.0))));
        } else {
            // Only commit cooldown on uncancelled interaction; event priority HIGHEST.
            pearls.put(p.getUniqueId(),current+cooldown*1000L);
        }
    }
    private int pearlCooldown(Location location) {
        int base=Math.max(0,getConfig().getInt("ender-pearls.default-cooldown-seconds",20));
        ConfigurationSection zones=getConfig().getConfigurationSection("ender-pearls.zone-cooldowns");
        if (zones!=null) for (String name:zones.getKeys(false)) {
            Area a=arenas.get(name.toLowerCase(Locale.ROOT));
            if (a!=null && a.contains(location)) return Math.max(0,zones.getInt(name,base));
        }
        return base;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void consume(PlayerItemConsumeEvent e) {
        if (active(e.getPlayer()) && getConfig().getBoolean("combat.block-chorus-fruit",true) && e.getItem().getType()==Material.CHORUS_FRUIT) {
            e.setCancelled(true);warning(e.getPlayer(),"blocked-chorus");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void flight(PlayerToggleFlightEvent e) {
        if (e.isFlying() && active(e.getPlayer()) && e.getPlayer().getGameMode()!=GameMode.CREATIVE && e.getPlayer().getGameMode()!=GameMode.SPECTATOR) {
            e.setCancelled(true);warning(e.getPlayer(),"blocked-fly");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fall(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause()!=EntityDamageEvent.DamageCause.FALL) return;
        Long until=fallSafe.get(p.getUniqueId());
        if (until!=null) { fallSafe.remove(p.getUniqueId()); if (until>now()) e.setCancelled(true); }
    }
    @EventHandler public void death(PlayerDeathEvent e) {
        UUID id=e.getEntity().getUniqueId();
        tagged.remove(id);endNotice.remove(id); fallSafe.remove(id);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        Player p=e.getPlayer();boolean fighting=active(p);
        tagged.remove(p.getUniqueId());endNotice.remove(p.getUniqueId());fallSafe.remove(p.getUniqueId());
        if (!fighting || !getConfig().getBoolean("combat.logout.enabled",true)) return;
        // Respect the REAL gamerule, so keepInventory=true ALWAYS exempts a player from combat-log punishment.
        Boolean keep=p.getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY);
        if (Boolean.TRUE.equals(keep) && getConfig().getBoolean("combat.logout.respect-keep-inventory",true)) return;
        if (getConfig().getBoolean("combat.logout.kill-without-keep-inventory",true) && !p.isDead()) p.setHealth(0.0);
    }
    private void tick() {
        long now=now();
        for (Player p:Bukkit.getOnlinePlayers()) {
            UUID id=p.getUniqueId();
            if (tagged.containsKey(id)) {
                int remaining=seconds(p);
                if (remaining<=0) {
                    tagged.remove(id);
                    endNotice.put(id,now+Math.max(0,messages.getInt("ended-actionbar-seconds",3))*1000L);
                } else {
                    actionBar(p,raw("actionbar").replace("{seconds}",String.valueOf(remaining)));
                    if (p.getGameMode()!=GameMode.CREATIVE && p.getGameMode()!=GameMode.SPECTATOR && p.isFlying()) enforceFlight(p);
                    continue;
                }
            }
            Long until=endNotice.get(id);
            if (until!=null) {
                if (now<until) actionBar(p,raw("ended-actionbar"));
                else endNotice.remove(id);
            }
        }
        pearls.entrySet().removeIf(entry->entry.getValue()<now-30000);
        lastWarnings.entrySet().removeIf(entry->entry.getValue()<now-30000);
    }
    private void actionBar(Player p,String message) { p.spigot().sendMessage(ChatMessageType.ACTION_BAR,TextComponent.fromLegacyText(color(message))); }
    private String raw(String key) {return messages.getString(key,"&c"+key);}
    private String color(String s) {
        Matcher m=HEX.matcher(s);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(ChatColor.of("#"+m.group(1)).toString()));
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&',out.toString());
    }
    private void msg(CommandSender target,String key) {msg(target,key,Map.of());}
    private void msg(CommandSender target,String key,Map<String,String> values) {
        String value=raw(key);
        for(var entry:values.entrySet())value=value.replace("{"+entry.getKey()+"}",entry.getValue());
        target.sendMessage(color(raw("prefix")+value));
    }
    private void warning(Player p,String key) {warning(p,key,Map.of("seconds",String.valueOf(seconds(p))));}
    private void warning(Player p,String key,Map<String,String> vars) {
        long time=now(),previous=lastWarnings.getOrDefault(p.getUniqueId(),0L);
        if(time-previous<getConfig().getLong("messages.anti-spam-milliseconds",1500))return;
        lastWarnings.put(p.getUniqueId(),time);msg(p,key,vars);
    }
    private boolean permit(CommandSender who,String action) {
        if (who.hasPermission("bastioncombat.admin."+action))return true;
        msg(who,"no-permission");return false;
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0) {msg(sender,"usage");return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        switch(sub) {
            case "reload" -> {if(!permit(sender,"reload"))return true;reloadExtra();msg(sender,"reloaded");}
            case "status","add","remove" -> {
                if(!permit(sender,sub))return true;
                if(args.length<2) {msg(sender,"usage");return true;}
                Player target=Bukkit.getPlayerExact(args[1]);if(target==null){msg(sender,"player-not-found");return true;}
                switch(sub) {
                    case "status" -> msg(sender,active(target)?"status-active":"status-clear",Map.of("player",target.getName(),"seconds",String.valueOf(seconds(target))));
                    case "add" -> {tagged.put(target.getUniqueId(),now()+duration()*1000L);enforceFlight(target);msg(sender,"added",Map.of("player",target.getName()));}
                    case "remove" -> {tagged.remove(target.getUniqueId());endNotice.remove(target.getUniqueId());msg(sender,"removed",Map.of("player",target.getName()));}
                }
            }
            case "arenas" -> {if(!permit(sender,"arenas"))return true;msg(sender,"arena-list",Map.of("arenas",arenas.isEmpty()?"(ninguna)":String.join(", ",arenas.keySet())));}
            case "delarena" -> {
                if(!permit(sender,"arenas"))return true;
                if(args.length<2){msg(sender,"usage");return true;}
                String id=args[1].toLowerCase(Locale.ROOT);
                if(arenas.remove(id)==null)msg(sender,"arena-missing");
                else {saveArenas();msg(sender,"arena-deleted",Map.of("arena",id));}
            }
            case "setarena" -> {
                if(!permit(sender,"arenas"))return true;
                if(!(sender instanceof Player player)){msg(sender,"only-player");return true;}
                if(args.length<3 || (!args[2].equals("1")&&!args[2].equals("2"))){msg(sender,"usage");return true;}
                String id=args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","");
                if(id.isEmpty()) {msg(sender,"usage");return true;}
                Location location=player.getLocation();
                Area old=arenas.get(id);
                if(old!=null && !old.world.equals(location.getWorld().getName())) {msg(sender,"arena-worlds");return true;}
                Area area=old==null?new Area(location.getWorld().getName()):old;
                Point point=new Point(location.getBlockX(),location.getBlockY(),location.getBlockZ());
                if(args[2].equals("1"))area.first=point;else area.second=point;
                arenas.put(id,area);saveArenas();
                msg(sender,"corner-set",Map.of("corner",args[2],"arena",id));
                if(area.first!=null && area.second!=null)msg(sender,"arena-ready",Map.of("arena",id));
            }
            default -> msg(sender,"usage");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] args) {
        if(args.length==1) {
            List<String> options=new ArrayList<>();
            for(String s:List.of("reload","status","add","remove","setarena","delarena","arenas"))
                if(sender.hasPermission("bastioncombat.admin."+(s.equals("setarena")||s.equals("delarena")?"arenas":s))&&s.startsWith(args[0].toLowerCase(Locale.ROOT)))options.add(s);
            return options;
        }
        if(args.length==2 && List.of("status","add","remove").contains(args[0].toLowerCase(Locale.ROOT)))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && List.of("delarena","setarena").contains(args[0].toLowerCase(Locale.ROOT)))
            return arenas.keySet().stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3 && args[0].equalsIgnoreCase("setarena"))return List.of("1","2");
        return List.of();
    }
}
