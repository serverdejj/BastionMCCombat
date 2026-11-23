package mc.bastion.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;

final class ProtectionsBridge {
 private ProtectionsBridge(){}
 static boolean isProtected(Location loc) {
  if(loc==null || !Bukkit.getPluginManager().isPluginEnabled("BastionMCProtections"))return false;
  Plugin plugin=Bukkit.getPluginManager().getPlugin("BastionMCProtections");
  try {
   Method method=plugin.getClass().getMethod("isProtectionAt",Location.class);
   return Boolean.TRUE.equals(method.invoke(plugin,loc));
  }catch(ReflectiveOperationException ex){return false;}
 }
}
