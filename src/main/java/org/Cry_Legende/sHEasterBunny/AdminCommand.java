package org.Cry_Legende.sHEasterBunny;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /sheasterbunny : reload et info. */
final class AdminCommand implements TabExecutor {

    static final String RELOAD_PERMISSION = "sheasterbunny.admin.reload";
    static final String INFO_PERMISSION = "sheasterbunny.admin.info";

    private final SHEasterBunny plugin;

    AdminCommand(SHEasterBunny plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Messages messages = plugin.messages();
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            messages.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && allowed.contains("reload")) {
            String error = plugin.reloadAll();
            if (error == null) {
                messages.send(sender, "reloaded");
            } else {
                messages.send(sender, "reload-failed", "error", error);
            }
        } else if (sub.equals("info") && allowed.contains("info")) {
            if (args.length < 2) {
                messages.send(sender, "usage-info");
            } else {
                plugin.sendInfo(sender, args[1]);
            }
        } else {
            messages.send(sender, "usage", "subs", String.join("|", allowed));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> allowed = allowed(sender);
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.addAll(allowed);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info") && allowed.contains("info")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add(player.getName());
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(candidate -> !candidate.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }

    private static List<String> allowed(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission(RELOAD_PERMISSION)) {
            subs.add("reload");
        }
        if (sender.hasPermission(INFO_PERMISSION)) {
            subs.add("info");
        }
        return subs;
    }
}
