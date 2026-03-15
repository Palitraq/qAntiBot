package ru.palitraq.qantibot;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

public class qAntiBot extends JavaPlugin implements Listener, CommandExecutor {
   private final Map<String, Long> verifiedPlayers = new HashMap();
   private final Map<String, Long> bannedIps = new HashMap();
   private final Map<String, Integer> kickCounts = new HashMap();
   private final Set<UUID> pendingVerification = new HashSet();
   private final Map<UUID, Integer> playerRounds = new HashMap();
   private final Map<UUID, Material> targetBlocks = new HashMap();
   private final Map<UUID, BukkitTask> timeoutTasks = new HashMap();
   private final Set<UUID> reopeningInventory = new HashSet();
   private final Map<Material, String> captchaItems = new HashMap();
   private File dataFile;
   private FileConfiguration dataConfig;

   public void onEnable() {
      this.saveDefaultConfig();
      this.loadItems();
      this.setupDataFile();
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getCommand("qab").setExecutor(this);
      this.getLogger().info("qAntiBot успешно запущен!");
   }

   public void onDisable() {
      this.saveDataFile();
      this.getLogger().info("qAntiBot выключен!");
   }

   private void loadItems() {
      this.captchaItems.clear();
      FileConfiguration cfg = this.getConfig();
      if (cfg.contains("items")) {
         Iterator var2 = cfg.getConfigurationSection("items").getKeys(false).iterator();

         while(var2.hasNext()) {
            String key = (String)var2.next();
            Material mat = Material.matchMaterial(key);
            if (mat != null) {
               this.captchaItems.put(mat, cfg.getString("items." + key));
            }
         }
      }

   }

   private void setupDataFile() {
      this.dataFile = new File(this.getDataFolder(), "data.yml");
      if (!this.dataFile.exists()) {
         try {
            this.dataFile.createNewFile();
         } catch (IOException var3) {
            var3.printStackTrace();
         }
      }

      this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
      Iterator var1;
      String key;
      if (this.dataConfig.contains("verified")) {
         var1 = this.dataConfig.getConfigurationSection("verified").getKeys(false).iterator();

         while(var1.hasNext()) {
            key = (String)var1.next();
            key = key.replace("''", "");
            this.verifiedPlayers.put(key, this.dataConfig.getLong("verified.''" + key + "''"));
         }
      }

      if (this.dataConfig.contains("banned")) {
         var1 = this.dataConfig.getConfigurationSection("banned").getKeys(false).iterator();

         while(var1.hasNext()) {
            key = (String)var1.next();
            key = key.replace("''", "");
            this.bannedIps.put(key, this.dataConfig.getLong("banned.''" + key + "''"));
         }
      }

      if (this.dataConfig.contains("kicks")) {
         var1 = this.dataConfig.getConfigurationSection("kicks").getKeys(false).iterator();

         while(var1.hasNext()) {
            key = (String)var1.next();
            key = key.replace("''", "");
            this.kickCounts.put(key, this.dataConfig.getInt("kicks.''" + key + "''"));
         }
      }

      this.cleanupData();
   }

   private void saveDataFile() {
      this.cleanupData();
      this.dataConfig.set("verified", (Object)null);
      this.dataConfig.set("banned", (Object)null);
      this.dataConfig.set("kicks", (Object)null);
      this.verifiedPlayers.forEach((k, v) -> {
         this.dataConfig.set("verified.''" + k + "''", v);
      });
      this.bannedIps.forEach((k, v) -> {
         this.dataConfig.set("banned.''" + k + "''", v);
      });
      this.kickCounts.forEach((k, v) -> {
         this.dataConfig.set("kicks.''" + k + "''", v);
      });

      try {
         this.dataConfig.save(this.dataFile);
      } catch (IOException var2) {
         var2.printStackTrace();
      }

   }

   private void cleanupData() {
      long now = System.currentTimeMillis();
      this.verifiedPlayers.entrySet().removeIf((entry) -> {
         return (Long)entry.getValue() < now;
      });
      this.bannedIps.entrySet().removeIf((entry) -> {
         return (Long)entry.getValue() < now;
      });
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      String ip = p.getAddress().getAddress().getHostAddress();
      String var10000 = p.getName();
      String verifyKey = var10000 + "_" + ip;
      long now = System.currentTimeMillis();
      Bukkit.getScheduler().runTaskLater(this, () -> {
         p.getInventory().setHeldItemSlot(4);
      }, 1L);
      if (this.bannedIps.containsKey(ip)) {
         if ((Long)this.bannedIps.get(ip) > now) {
            p.kickPlayer(this.getMessage("messages.ban-reason"));
            return;
         }

         this.bannedIps.remove(ip);
         this.kickCounts.remove(ip);
      }

      if (!p.hasPermission("qantibot.bypass") && (!this.verifiedPlayers.containsKey(verifyKey) || (Long)this.verifiedPlayers.get(verifyKey) <= now)) {
         this.pendingVerification.add(p.getUniqueId());
         Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
               this.startVerification(p);
            }

         }, 20L);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.cleanPlayerTasks(e.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onMove(PlayerMoveEvent e) {
      if (this.pendingVerification.contains(e.getPlayer().getUniqueId()) || this.playerRounds.containsKey(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }

   }

   @EventHandler
   public void onChat(AsyncPlayerChatEvent e) {
      if (this.pendingVerification.contains(e.getPlayer().getUniqueId()) || this.playerRounds.containsKey(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }

   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent e) {
      Player p = (Player)e.getWhoClicked();
      UUID uuid = p.getUniqueId();
      if (this.playerRounds.containsKey(uuid)) {
         e.setCancelled(true);
         if (e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.CHEST) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && clicked.getType() != Material.AIR) {
               Material target = (Material)this.targetBlocks.get(uuid);
               if (clicked.getType() == target) {
                  int currentRound = (Integer)this.playerRounds.get(uuid);
                  if (currentRound >= 3) {
                     this.passVerification(p);
                  } else {
                     this.openCaptchaGUI(p, currentRound + 1);
                  }
               } else {
                  this.failVerification(p, "kick-reason");
               }

            }
         }
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent e) {
      Player p = (Player)e.getPlayer();
      UUID uuid = p.getUniqueId();
      if (this.playerRounds.containsKey(uuid) && !this.reopeningInventory.contains(uuid)) {
         Bukkit.getScheduler().runTask(this, () -> {
            this.failVerification(p, "kick-reason");
         });
      }

   }

   private void startVerification(Player p) {
      this.pendingVerification.remove(p.getUniqueId());
      this.openCaptchaGUI(p, 1);
   }

   private void openCaptchaGUI(Player p, int round) {
      UUID uuid = p.getUniqueId();
      this.cancelTimeout(uuid);
      List<Material> available = new ArrayList(this.captchaItems.keySet());
      Collections.shuffle(available);
      List<Material> guiBlocks = available.subList(0, Math.min(45, available.size()));
      Material targetBlock = (Material)guiBlocks.get((new Random()).nextInt(guiBlocks.size()));
      this.targetBlocks.put(uuid, targetBlock);
      this.playerRounds.put(uuid, round);
      String titleHeader = this.getConfig().getString("messages.title-header", "&8Найди: ");
      String targetName = (String)this.captchaItems.get(targetBlock);
      Inventory inv = Bukkit.createInventory((InventoryHolder)null, 45, this.color(titleHeader + targetName));

      int i;
      for(i = 0; i < guiBlocks.size(); ++i) {
         Material mat = (Material)guiBlocks.get(i);
         ItemStack item = new ItemStack(mat);
         ItemMeta meta = item.getItemMeta();
         if (meta != null) {
            Object var10002 = this.captchaItems.get(mat);
            meta.setDisplayName(this.color("&r" + (String)var10002));
            item.setItemMeta(meta);
         }

         inv.setItem(i, item);
      }

      this.reopeningInventory.add(uuid);
      p.openInventory(inv);
      this.reopeningInventory.remove(uuid);
      i = this.getConfig().getInt("settings.time-per-round", 10);
      this.timeoutTasks.put(uuid, Bukkit.getScheduler().runTaskLater(this, () -> {
         if (this.playerRounds.containsKey(uuid)) {
            this.failVerification(p, "timer-expired");
         }

      }, (long)i * 20L));
   }

   private void passVerification(Player p) {
      UUID uuid = p.getUniqueId();
      this.cleanPlayerTasks(uuid);
      String ip = p.getAddress().getAddress().getHostAddress();
      String var10000 = p.getName();
      String verifyKey = var10000 + "_" + ip;
      int days = this.getConfig().getInt("settings.immunity-days", 3);
      this.verifiedPlayers.put(verifyKey, System.currentTimeMillis() + (long)days * 86400000L);
      this.kickCounts.remove(ip);
      BukkitScheduler var6 = Bukkit.getScheduler();
      Objects.requireNonNull(p);
      var6.runTask(this, p::closeInventory);
      p.sendMessage(this.getMessage("messages.success"));
   }

   private void failVerification(Player p, String reasonKey) {
      UUID uuid = p.getUniqueId();
      this.cleanPlayerTasks(uuid);
      String ip = p.getAddress().getAddress().getHostAddress();
      int kicks = (Integer)this.kickCounts.getOrDefault(ip, 0) + 1;
      int maxKicks = this.getConfig().getInt("settings.max-kicks-before-ban", 3);
      if (kicks >= maxKicks) {
         int banMinutes = this.getConfig().getInt("settings.ban-time-minutes", 5);
         this.bannedIps.put(ip, System.currentTimeMillis() + (long)banMinutes * 60000L);
         this.kickCounts.remove(ip);
         p.kickPlayer(this.getMessage("messages.ban-reason"));
      } else {
         this.kickCounts.put(ip, kicks);
         p.kickPlayer(this.getMessage("messages." + reasonKey));
      }

   }

   private void cleanPlayerTasks(UUID uuid) {
      this.pendingVerification.remove(uuid);
      this.playerRounds.remove(uuid);
      this.targetBlocks.remove(uuid);
      this.cancelTimeout(uuid);
   }

   private void cancelTimeout(UUID uuid) {
      if (this.timeoutTasks.containsKey(uuid)) {
         ((BukkitTask)this.timeoutTasks.get(uuid)).cancel();
         this.timeoutTasks.remove(uuid);
      }

   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("qantibot.admin")) {
         sender.sendMessage(this.color("&cНет прав."));
         return true;
      } else if (args.length == 0) {
         sender.sendMessage(this.color("&b/qab reload &7- Перезагрузить конфиг"));
         sender.sendMessage(this.color("&b/qab unban <ip> &7- Разбанить IP"));
         sender.sendMessage(this.color("&b/qab force <игрок> &7- Принудительно пропустить капчу"));
         sender.sendMessage(this.color("&b/qab captcha <игрок> &7- Принудительно запустить капчу"));
         return true;
      } else if (args[0].equalsIgnoreCase("reload")) {
         this.reloadConfig();
         this.loadItems();
         sender.sendMessage(this.getMessage("messages.reload"));
         return true;
      } else if (args[0].equalsIgnoreCase("unban") && args.length > 1) {
         String ip = args[1];
         if (this.bannedIps.remove(ip) != null) {
            sender.sendMessage(this.getMessage("messages.unban-success").replace("%ip%", ip));
         } else {
            sender.sendMessage(this.getMessage("messages.unban-fail").replace("%ip%", ip));
         }

         return true;
      } else if (args[0].equalsIgnoreCase("force") && args.length > 1) {
         Player p = Bukkit.getPlayer(args[1]);
         if (p != null) {
            this.passVerification(p);
            sender.sendMessage(this.getMessage("messages.forced-pass").replace("%player%", p.getName()));
         } else {
            sender.sendMessage(this.getMessage("messages.player-not-found"));
         }

         return true;
      } else if (args[0].equalsIgnoreCase("captcha") && args.length > 1) {
         Player p = Bukkit.getPlayer(args[1]);
         if (p != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            String verifyKey = p.getName() + "_" + ip;
            this.verifiedPlayers.remove(verifyKey);
            this.pendingVerification.add(p.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> {
               if (p.isOnline()) {
                  this.startVerification(p);
               }
            }, 20L);
            sender.sendMessage(this.getMessage("messages.forced-start").replace("%player%", p.getName()));
         } else {
            sender.sendMessage(this.getMessage("messages.player-not-found"));
         }

         return true;
      } else {
         return true;
      }
   }

   private String getMessage(String path) {
      String prefix = this.getConfig().getString("messages.prefix", "&8[&bqAnti&fBot&8] ");
      String msg = this.getConfig().getString(path, "");
      return this.color(prefix + msg).replace("\\n", "\n");
   }

   private String color(String text) {
      if (text == null) {
         return "";
      } else {
         Pattern gradientPattern = Pattern.compile("<gradient:(#[A-Fa-f0-9]{6}):(#[A-Fa-f0-9]{6})>(.*?)</gradient>");
         Matcher gradientMatcher = gradientPattern.matcher(text);
         StringBuffer sb = new StringBuffer();

         while(gradientMatcher.find()) {
            Color start = Color.decode(gradientMatcher.group(1));
            Color end = Color.decode(gradientMatcher.group(2));
            String content = gradientMatcher.group(3);
            StringBuilder gradientStr = new StringBuilder();
            int length = content.length();

            for(int i = 0; i < length; ++i) {
               float ratio = (float)i / (float)(length <= 1 ? 1 : length - 1);
               int r = (int)((float)start.getRed() + ratio * (float)(end.getRed() - start.getRed()));
               int g = (int)((float)start.getGreen() + ratio * (float)(end.getGreen() - start.getGreen()));
               int b = (int)((float)start.getBlue() + ratio * (float)(end.getBlue() - start.getBlue()));
               gradientStr.append(ChatColor.of(new Color(r, g, b))).append(content.charAt(i));
            }

            gradientMatcher.appendReplacement(sb, gradientStr.toString());
         }

         gradientMatcher.appendTail(sb);
         text = sb.toString();
         Pattern hexPattern = Pattern.compile("[&<]#([A-Fa-f0-9]{6})>?");
         Matcher hexMatcher = hexPattern.matcher(text);
         StringBuffer hexSb = new StringBuffer();

         while(hexMatcher.find()) {
            hexMatcher.appendReplacement(hexSb, ChatColor.of("#" + hexMatcher.group(1)).toString());
         }

         hexMatcher.appendTail(hexSb);
         return ChatColor.translateAlternateColorCodes('&', hexSb.toString());
      }
   }
}
