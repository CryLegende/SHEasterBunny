package org.Cry_Legende.sHEasterBunny;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SHEasterBunny extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private File dataFile;
    private FileConfiguration dataConfig;

    // UUIDs des lapins en jeu
    private final Set<String> spawnedRabbits = new HashSet<>();
    // Marque les lapins dorés (dans le PDC du lapin, plus de FixedMetadataValue)
    private NamespacedKey goldenKey;

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

    // data.yml : écrit hors du thread principal, au plus toutes les 10 s, et seulement si quelque chose a changé
    private boolean dirty;
    private long saveSeq;
    private long writtenSeq;
    private final Object fileLock = new Object();

    @Override
    public void onEnable() {
        goldenKey = new NamespacedKey(this, "golden");
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

        new BukkitRunnable() {
            @Override
            public void run() {
                if (dirty) {
                    dirty = false;
                    saveDataAsync();
                }
            }
        }.runTaskTimer(this, 200L, 200L);
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

        // Après la lecture des compteurs : le reset du jour ne doit plus jamais vider les totaux avant qu'ils soient relus
        resetDailyIfNeeded();
    }

    /** Écriture synchrone (arrêt du plugin). */
    private void saveData() {
        if (dataConfig == null || dataFile == null) {
            return;
        }
        writeData(snapshotData(), ++saveSeq);
    }

    /** L'instantané est pris sur le thread principal, seule l'écriture du fichier part en asynchrone. */
    private void saveDataAsync() {
        if (dataConfig == null || dataFile == null) {
            return;
        }
        String data = snapshotData();
        long seq = ++saveSeq;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeData(data, seq));
    }

    private String snapshotData() {
        dataConfig.set("daily.date", dailyCounterDate);
        dataConfig.set("daily.counts", null);
        for (Map.Entry<UUID, Integer> entry : dailyCaughtRabbits.entrySet()) {
            dataConfig.set("daily.counts." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("caughtRabbits", null);
        for (Map.Entry<UUID, Integer> entry : caughtRabbits.entrySet()) {
            dataConfig.set("caughtRabbits." + entry.getKey(), entry.getValue());
        }

        return dataConfig.saveToString();
    }

    private void writeData(String data, long seq) {
        synchronized (fileLock) {
            if (seq < writtenSeq) {
                return; // un instantané plus récent a déjà été écrit
            }
            // Fichier temporaire puis remplacement : un arrêt en plein milieu ne tronque pas data.yml
            File tmp = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
            try {
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                writtenSeq = seq;
            } catch (IOException e) {
                getLogger().severe("Erreur lors de la sauvegarde du fichier data.yml");
                e.printStackTrace();
            }
        }
    }

    private void resetDailyIfNeeded() {
        String today = LocalDate.now().toString();
        if (dailyCounterDate == null || !dailyCounterDate.equals(today)) {
            dailyCounterDate = today;
            dailyCaughtRabbits.clear();
            dirty = true;
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
    private World spawnWorld() {
        String name = config.getString("spawn-zone.world");
        return name == null ? null : Bukkit.getWorld(name);
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

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
        World world = spawnWorld();
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
            rabbit.getPersistentDataContainer().set(goldenKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            rabbit.setCustomName(normalName);
        }
        rabbit.setCustomNameVisible(true);
        spawnedRabbits.add(rabbit.getUniqueId().toString());

        Sound s = sound(config.getString("spawn-sound"));
        if (s != null) {
            world.playSound(spawnLocation, s, 1.0f, 1.0f);
        } else {
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
        boolean isGolden = rabbit.getPersistentDataContainer().has(goldenKey, PersistentDataType.BYTE);

        caughtRabbits.put(player.getUniqueId(), caughtRabbits.getOrDefault(player.getUniqueId(), 0) + 1);
        if (rewardsAllowed) {
            dailyCaughtRabbits.put(player.getUniqueId(), currentDaily + 1);
        }
        dirty = true;

        spawnedRabbits.remove(rid.toString());
        rabbit.remove();

        if (rewardsAllowed) {
            for (String cmd : (isGolden ? config.getStringList("golden-rabbit.commands") : config.getStringList("commands"))) {
                if (cmd != null && !cmd.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                }
            }
        }

        Sound cs = sound(config.getString("click-sound"));
        if (cs != null) {
            loc.getWorld().playSound(loc, cs, 1.0f, 1.0f);
        } else {
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
    public void onQuit(PlayerQuitEvent event) {
        dailyLimitMessageCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (spawnedRabbits.isEmpty()) {
            return;
        }
        Chunk chunk = event.getChunk();
        for (Iterator<String> it = spawnedRabbits.iterator(); it.hasNext(); ) {
            String uuidString = it.next();
            UUID rid = UUID.fromString(uuidString);
            Entity entity = Bukkit.getEntity(rid);
            // Comparaison par coordonnées : pas de création d'objet Chunk pour chaque lapin à chaque déchargement
            if (entity != null && entity.getWorld().equals(chunk.getWorld())
                && (entity.getLocation().getBlockX() >> 4) == chunk.getX()
                && (entity.getLocation().getBlockZ() >> 4) == chunk.getZ()) {
                entity.remove();
                it.remove();
                BukkitTask pending = despawnTasks.remove(rid);
                if (pending != null) {
                    pending.cancel();
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        spawnRabbits();
                    }
                }.runTaskLater(this, config.getInt("spawn-interval-ticks", 400));
            }
        }
    }

    // Nettoyage des lapins restés dans le monde de l'événement (arrêt brutal, chunk déchargé avant onDisable).
    // EntitiesLoadEvent : les entités d'un chunk sont chargées après ChunkLoadEvent, la liste y serait vide.
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World cfgWorld = spawnWorld();
        if (cfgWorld == null || !event.getWorld().equals(cfgWorld)) {
            return;
        }

        // Seuls les lapins de l'événement (reconnus à leur nom) non apparus depuis ce démarrage sont supprimés :
        // les autres lapins du monde (décor, animaux des joueurs) ne sont pas touchés
        String normalName = colorize(config.getString("custom-name", "Attrape-moi !"));
        String goldenName = colorize(config.getString("golden-rabbit.custom-name", normalName));
        for (Entity ent : event.getEntities()) {
            if (ent instanceof Rabbit rabbit && !spawnedRabbits.contains(rabbit.getUniqueId().toString())) {
                String name = rabbit.getCustomName();
                if (normalName.equals(name) || goldenName.equals(name)) {
                    rabbit.remove();
                }
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

        // Sans cela, /papi reload désenregistre le placeholder
        @Override
        public boolean persist() {
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

