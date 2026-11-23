package mc.bastion.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/** Soft dependency. Reflection allows builds with Paper alone while using WorldGuard 7 when present. */
final class WorldGuardBridge {
    private WorldGuardBridge() {}
    static boolean isSafe(Location location, FileConfiguration config) {
        if (!config.getBoolean("worldguard.enabled",true) || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return false;
        try {
            Class<?> wgClass=Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg=wgClass.getMethod("getInstance").invoke(null);
            Object platform=wgClass.getMethod("getPlatform").invoke(wg);
            Object container=platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query=container.getClass().getMethod("createQuery").invoke(container);
            Class<?> adapter=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Method adapt=adapter.getMethod("adapt",Location.class);
            Object weLocation=adapt.invoke(null,location);
            Method regionsMethod=null;
            for(Method m:query.getClass().getMethods())
                if(m.getName().equals("getApplicableRegions") && m.getParameterCount()==1 && m.getParameterTypes()[0].isInstance(weLocation)) {regionsMethod=m;break;}
            if(regionsMethod==null)return false;
            Object regions=regionsMethod.invoke(query,weLocation);
            List<String> names=config.getStringList("worldguard.safe-region-ids");
            if(!names.isEmpty()) {
                Object entries=regions.getClass().getMethod("getRegions").invoke(regions);
                if(entries instanceof Collection<?> list) for(Object region:list) {
                    String id=String.valueOf(region.getClass().getMethod("getId").invoke(region));
                    if(names.stream().anyMatch(s->s.equalsIgnoreCase(id)))return true;
                }
            }
            if (!config.getBoolean("worldguard.block-pvp-deny-regions",true))return false;
            Class<?> flags=Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Object pvp=flags.getField("PVP").get(null);
            Class<?> stateFlag=Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Object array=Array.newInstance(stateFlag,1);Array.set(array,0,pvp);
            for(Method m:regions.getClass().getMethods())
                if(m.getName().equals("queryState") && m.getParameterCount()==2) {
                    Object state=m.invoke(regions,null,array);
                    return state!=null && state.toString().equals("DENY");
                }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // If another version of WorldGuard changes its API, do not block all movement.
        }
        return false;
    }
}
