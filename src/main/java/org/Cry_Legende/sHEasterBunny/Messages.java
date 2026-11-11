package org.Cry_Legende.sHEasterBunny;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Textes de messages.yml. Une clé absente du fichier du serveur reprend le texte livré dans le jar.
 * Au premier démarrage sans messages.yml, le fichier est créé à partir de l'ancienne section messages: de config.yml
 * (textes du serveur conservés), complétée par les textes du jar ; cette section n'est plus lue ensuite.
 */
final class Messages {

    private final JavaPlugin plugin;
    private final File file;
    private volatile YamlConfiguration messages = new YamlConfiguration();

    Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    /** Lit messages.yml au démarrage. Illisible : les textes du jar sont utilisés et l'erreur est écrite dans la console. */
    void load() {
        try {
            messages = read();
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("messages.yml illisible, textes livrés utilisés : " + describe(e));
            messages = defaults();
        }
    }

    /** Lit messages.yml sans rien remplacer : à appliquer avec {@link #apply} une fois tous les fichiers relus. */
    YamlConfiguration read() throws IOException, InvalidConfigurationException {
        if (!file.exists()) {
            migrate();
        }
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(file);
        loaded.setDefaults(defaults());
        return loaded;
    }

    void apply(YamlConfiguration loaded) {
        messages = loaded;
    }

    /** Texte brut (sans couleurs), vide si la clé est absente. */
    String raw(String path) {
        String value = messages.getString(path);
        return value == null ? "" : value;
    }

    /** Envoie le message ; {@code pairs} = nom, valeur, nom, valeur... pour les variables %nom%. Un message vide n'est pas envoyé. */
    void send(CommandSender to, String path, String... pairs) {
        if (messages.isList(path)) {
            for (String line : messages.getStringList(path)) {
                deliver(to, line, pairs);
            }
            return;
        }
        deliver(to, raw(path), pairs);
    }

    static String apply(String text, String... pairs) {
        String result = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace("%" + pairs[i] + "%", pairs[i + 1]);
        }
        return result;
    }

    /** Message d'une exception de lecture YAML sur une seule ligne. */
    static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static void deliver(CommandSender to, String text, String... pairs) {
        if (text == null || text.isEmpty()) {
            return;
        }
        to.sendMessage(ChatColor.translateAlternateColorCodes('&', apply(text, pairs)));
    }

    private YamlConfiguration defaults() {
        YamlConfiguration jar = new YamlConfiguration();
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                jar.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("messages.yml livré dans le jar illisible : " + describe(e));
        }
        return jar;
    }

    /** Crée messages.yml : textes du jar, remplacés par ceux de l'ancienne section messages: de config.yml. */
    private void migrate() throws IOException {
        YamlConfiguration created = defaults();
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("messages");
        if (legacy != null) {
            List<String> keys = new ArrayList<>(legacy.getKeys(true));
            for (String key : keys) {
                if (!legacy.isConfigurationSection(key)) {
                    created.set(key, legacy.get(key));
                }
            }
        }
        plugin.getDataFolder().mkdirs();
        created.save(file);
    }
}
