package org.Cry_Legende.sHEasterBunny;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SHEasterBunny extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private File dataFile;
    private FileConfiguration dataConfig;

    // UUIDs des lapins en jeu
    private final Set<String> spawnedRabbits = new HashSet<>();
    private static final String METADATA_KEY = "sHEasterBunny";
    private static final String METADATA_KEY_GOLDEN = "goldenRabbit";

    // Timer de despawn
    private final Map<UUID, BukkitTask> despawnTasks = new HashMap<>();

    // Total attrape (historique)
    private final Map<UUID, Integer> caughtRabbits = new HashMap<>();

    // Attrape du jour
    private final Map<UUID, Integer> dailyCaughtRabbits = new HashMap<>();
    private String dailyCounterDate;
    private final Map<UUID, Long> dailyLimitMessageCooldowns = new HashMap<>();

    // Compteur de respawns a executer (une par une)
    private int pendingRespawns = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI hook enregistre !");
        } else {
            getLogger().warning("PlaceholderAPI non trouve. Le hook ne sera pas enregistre.");
        }

        int interval = config.getInt("spawn-interval-ticks", 400);
        new BukkitRunnable() {
            @Override
            public void run() {
                spawnRabbits();
            }
        }.runTaskTimer(this, 0, interval);
    }

    @Override
    public void onDisable() {
        for (String uuidString : spawnedRabbits) {
            try {
                Entity e = Bukkit.getEntity(UUID.fromString(uuidString));
                if (e != null) {
                    e.remove();
                }
            } catch (Exception ignored) {
            }
        }
        spawnedRabbits.clear();

        for (BukkitTask task : despawnTasks.values()) {
            task.cancel();
        }
        despawnTasks.clear();

        saveData();
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            saveResource("data.yml", false);
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        dailyCounterDate = dataConfig.getString("daily.date", LocalDate.now().toString());
        resetDailyIfNeeded();

        if (dataConfig.contains("caughtRabbits") && dataConfig.getConfigurationSection("caughtRabbits") != null) {
            for (String key : dataConfig.getConfigurationSection("caughtRabbits").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    int count = dataConfig.getInt("caughtRabbits." + key);
                    caughtRabbits.put(uuid, count);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide dans data.yml: " + key);
                }
            }
        }

        if (dataConfig.contains("daily.counts") && dataConfig.getConfigurationSection("daily.counts") != null) {
            for (String key : dataConfig.getConfigurationSection("daily.counts").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    int count = dataConfig.getInt("daily.counts." + key);
                    dailyCaughtRabbits.put(uuid, count);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide dans data.yml (daily): " + key);
                }
            }
        }
    }

    private void saveData() {
        if (dataConfig == null || dataFile == null) {
            return;
        }

        dataConfig.set("daily.date", dailyCounterDate);
        dataConfig.set("daily.counts", null);
        for (Map.Entry<UUID, Integer> entry : dailyCaughtRabbits.entrySet()) {
            dataConfig.set("daily.counts." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("caughtRabbits", null);
        for (Map.Entry<UUID, Integer> entry : caughtRabbits.entrySet()) {
            dataConfig.set("caughtRabbits." + entry.getKey(), entry.getValue());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Erreur lors de la sauvegarde du fichier data.yml");
            e.printStackTrace();
        }
    }

    private void resetDailyIfNeeded() {
        String today = LocalDate.now().toString();
        if (dailyCounterDate == null || !dailyCounterDate.equals(today)) {
            dailyCounterDate = today;
            dailyCaughtRabbits.clear();
            saveData();
        }
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private String formatMessage(String path, Player player, int dailyCount, int maxDaily) {
        String raw = config.getString(path, "");
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        int remaining = maxDaily < 0 ? -1 : Math.max(0, maxDaily - dailyCount);
        return colorize(raw)
            .replace("%player%", player.getName())
            .replace("%daily_count%", String.valueOf(dailyCount))
            .replace("%daily_max%", maxDaily < 0 ? "INF" : String.valueOf(maxDaily))
            .replace("%daily_remaining%", remaining < 0 ? "INF" : String.valueOf(remaining));
    }

    private boolean shouldSendDailyLimitMessage(Player player) {
        int cooldownSeconds = config.getInt("daily-limit.message-cooldown-seconds", 30);
        if (cooldownSeconds <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = cooldownSeconds * 1000L;
        UUID playerId = player.getUniqueId();
        long lastSent = dailyLimitMessageCooldowns.getOrDefault(playerId, 0L);

        if (now - lastSent < cooldownMs) {
            return false;
        }

        dailyLimitMessageCooldowns.put(playerId, now);
        return true;
    }

    /**
     * Retourne une location de spawn ou le bloc au sol est solide
     * et ou les deux blocs au-dessus sont de l'air.
     */
    private Location getValidSpawnLocation(World world) {
        double configMinX = config.getDouble("spawn-zone.min-x");
        double configMaxX = config.getDouble("spawn-zone.max-x");
        double configMinZ = config.getDouble("spawn-zone.min-z");
        double configMaxZ = config.getDouble("spawn-zone.max-z");
        int minY = config.getInt("spawn-zone.min-y");
        int maxY = config.getInt("spawn-zone.max-y");

        double minX = Math.min(configMinX, configMaxX);
        double maxX = Math.max(configMinX, configMaxX);
        double minZ = Math.min(configMinZ, configMaxZ);
        double maxZ = Math.max(configMinZ, configMaxZ);

        for (int attempt = 0; attempt < 20; attempt++) {
            int x = (int) (minX + Math.random() * (maxX - minX + 1));
            int z = (int) (minZ + Math.random() * (maxZ - minZ + 1));
            for (int y = maxY; y >= minY; y--) {
                Block ground = world.getBlockAt(x, y, z);
                if (!ground.getType().isAir()) {
                    Block above1 = world.getBlockAt(x, y + 1, z);
                    Block above2 = world.getBlockAt(x, y + 2, z);
                    if (above1.getType().isAir() && above2.getType().isAir()) {
                        return new Location(world, x + 0.5, y + 1, z + 0.5);
                    }
                    break;
                }
            }
        }

        int x = (int) (minX + Math.random() * (maxX - minX + 1));
        int z = (int) (minZ + Math.random() * (maxZ - minZ + 1));
        return new Location(world, x + 0.5, minY + 0.5, z + 0.5);
    }

    /**
     * Gere le spawn des lapins, incluant backlog de respawns.
     */
    public void spawnRabbits() {
        spawnedRabbits.removeIf(uuid -> Bukkit.getEntity(UUID.fromString(uuid)) == null);

        if (pendingRespawns > 0) {
            spawnOneRabbit();
            pendingRespawns--;
            return;
        }

        int maxRabbits = config.getInt("max-rabbits", 20);
        if (spawnedRabbits.size() >= maxRabbits) {
            return;
        }
        spawnOneRabbit();
    }

    /**
     * Logique de spawn d'un seul lapin.
     */
    private void spawnOneRabbit() {
        World world = Bukkit.getWorld(config.getString("spawn-zone.world"));
        if (world == null) {
            return;
        }

        Location spawnLocation = getValidSpawnLocation(world);
        Rabbit rabbit = world.spawn(spawnLocation, Rabbit.class);

        String normalName = colorize(config.getString("custom-name", "Attrape-moi !"));
        double goldenChance = config.getDouble("golden-rabbit.chance", 0.0);
        String goldenName = colorize(config.getString("golden-rabbit.custom-name", normalName));

        boolean isGolden = Math.random() < goldenChance;
        if (isGolden) {
            rabbit.setCustomName(goldenName);
            rabbit.setMetadata(METADATA_KEY_GOLDEN, new FixedMetadataValue(this, true));
        } else {
            rabbit.setCustomName(normalName);
            rabbit.setMetadata(METADATA_KEY, new FixedMetadataValue(this, true));
        }
        rabbit.setCustomNameVisible(true);
        spawnedRabbits.add(rabbit.getUniqueId().toString());

        try {
            Sound s = Sound.valueOf(config.getString("spawn-sound"));
            world.playSound(spawnLocation, s, 1.0f, 1.0f);
        } catch (Exception e) {
            getLogger().warning("Son de spawn invalide dans la config.");
        }

        UUID rid = rabbit.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Entity e = Bukkit.getEntity(rid);
                if (e instanceof Rabbit && spawnedRabbits.contains(rid.toString())) {
                    e.remove();
                    spawnedRabbits.remove(rid.toString());
                    spawnRabbits();
                }
                despawnTasks.remove(rid);
            }
        }.runTaskLater(this, 10 * 60 * 20L);
        despawnTasks.put(rid, task);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Rabbit)) {
            return;
        }

        Rabbit rabbit = (Rabbit) event.getRightClicked();
        if (!spawnedRabbits.contains(rabbit.getUniqueId().toString())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        resetDailyIfNeeded();

        boolean dailyLimitEnabled = config.getBoolean("daily-limit.enabled", false);
        int maxDaily = config.getInt("daily-limit.max-per-day", 200);
        int currentDaily = dailyCaughtRabbits.getOrDefault(player.getUniqueId(), 0);
        boolean rewardsAllowed = !(dailyLimitEnabled && maxDaily >= 0 && currentDaily >= maxDaily);

        UUID rid = rabbit.getUniqueId();
        BukkitTask pending = despawnTasks.remove(rid);
        if (pending != null) {
            pending.cancel();
        }

        Location loc = rabbit.getLocation();

        caughtRabbits.put(player.getUniqueId(), caughtRabbits.getOrDefault(player.getUniqueId(), 0) + 1);
        if (rewardsAllowed) {
            dailyCaughtRabbits.put(player.getUniqueId(), currentDaily + 1);
        }
        saveData();

        spawnedRabbits.remove(rid.toString());
        rabbit.remove();

        if (rewardsAllowed) {
            boolean isGolden = rabbit.hasMetadata(METADATA_KEY_GOLDEN);
            for (String cmd : (isGolden ? config.getStringList("golden-rabbit.commands") : config.getStringList("commands"))) {
                if (cmd != null && !cmd.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                }
            }
        }

        try {
            Sound cs = Sound.valueOf(config.getString("click-sound"));
            loc.getWorld().playSound(loc, cs, 1.0f, 1.0f);
        } catch (Exception e) {
            getLogger().warning("Son de clic invalide dans la config.");
        }

        try {
            Particle p = Particle.valueOf(config.getString("particle"));
            loc.getWorld().spawnParticle(p, loc, 10, 0.5, 0.5, 0.5, 0.1);
        } catch (Exception e) {
            getLogger().warning("Particule invalide dans la config.");
        }

        if (rewardsAllowed) {
            int newDaily = dailyCaughtRabbits.getOrDefault(player.getUniqueId(), 0);
            String catchMsg = formatMessage("messages.rabbit-caught", player, newDaily, maxDaily);
            if (!catchMsg.isEmpty()) {
                player.sendMessage(catchMsg);
            }
        } else if (shouldSendDailyLimitMessage(player)) {
            String msg = formatMessage("messages.daily-limit-reached", player, currentDaily, maxDaily);
            if (!msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }

        pendingRespawns++;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (Iterator<String> it = spawnedRabbits.iterator(); it.hasNext(); ) {
            String uuidString = it.next();
            Entity entity = Bukkit.getEntity(UUID.fromString(uuidString));
            if (entity != null && entity.getLocation().getChunk().equals(chunk)) {
                entity.remove();
                it.remove();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        spawnRabbits();
                    }
                }.runTaskLater(this, config.getInt("spawn-interval-ticks", 400));
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        World cfgWorld = Bukkit.getWorld(config.getString("spawn-zone.world"));
        if (cfgWorld == null) {
            return;
        }

        String normalName = colorize(config.getString("custom-name", "Attrape-moi !"));
        String goldenName = colorize(config.getString("golden-rabbit.custom-name", normalName));

        for (Entity ent : chunk.getEntities()) {
            if (!(ent instanceof Rabbit)) {
                continue;
            }
            Rabbit rabbit = (Rabbit) ent;
            if (!rabbit.getWorld().equals(cfgWorld)) {
                continue;
            }

            String name = rabbit.getCustomName();
            String uuidStr = rabbit.getUniqueId().toString();
            boolean isSpawned = spawnedRabbits.contains(uuidStr);

            if (normalName.equals(name)) {
                if (!rabbit.hasMetadata(METADATA_KEY) || !isSpawned) {
                    rabbit.remove();
                }
                continue;
            }

            if (goldenName.equals(name)) {
                if (!rabbit.hasMetadata(METADATA_KEY_GOLDEN) || !isSpawned) {
                    rabbit.remove();
                }
                continue;
            }

            if (!isSpawned) {
                rabbit.remove();
            }
        }
    }

    public int getCaughtRabbits(Player player) {
        return caughtRabbits.getOrDefault(player.getUniqueId(), 0);
    }

    public static class PlaceholderHook extends PlaceholderExpansion {
        private final SHEasterBunny plugin;

        public PlaceholderHook(SHEasterBunny plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public boolean register() {
            return super.register();
        }

        @Override
        public String getIdentifier() {
            return "sheasterbunny";
        }

        @Override
        public String getAuthor() {
            return plugin.getDescription().getAuthors().get(0);
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String id) {
            if (player == null) {
                return "";
            }
            if ("rabbits".equalsIgnoreCase(id)) {
                return String.valueOf(plugin.getCaughtRabbits(player));
            }
            return null;
        }
    }
}

