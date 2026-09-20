package com.example.chatheadtooltip;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ChatHeadTooltip extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final List<TooltipStyleConfig> loadedStyles = new ArrayList<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    
    private String reloadMessage;
    private String noPermissionMessage;

    public record TooltipStyleConfig(
            String permission,
            int weight,
            String tooltipStyleKey,
            String titleFormat,
            List<String> loreFormat
    ) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigAndStyles();
        
        getServer().getPluginManager().registerEvents(this, this);
        
        if (getCommand("chatheadtooltip") != null) {
            getCommand("chatheadtooltip").setExecutor(this);
            getCommand("chatheadtooltip").setTabCompleter(this);
        }
    }

    private void loadConfigAndStyles() {
        reloadConfig();
        loadedStyles.clear();
        
        reloadMessage = getConfig().getString("messages.reload", "<green>Configuration reloaded.");
        noPermissionMessage = getConfig().getString("messages.no-permission", "<red>No permission.");

        ConfigurationSection section = getConfig().getConfigurationSection("styles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection styleSection = section.getConfigurationSection(key);
                if (styleSection == null) continue;

                String permission = styleSection.getString("permission", "");
                int weight = styleSection.getInt("weight", 0);
                String tooltipStyle = styleSection.getString("tooltip-style", "minecraft:default");
                String title = styleSection.getString("title", "<yellow><player_name>");
                List<String> lore = styleSection.getStringList("lore");

                loadedStyles.add(new TooltipStyleConfig(permission, weight, tooltipStyle, title, lore));
            }
        }

        // Sort descending by weight so highest weight is evaluated first
        loadedStyles.sort(Comparator.comparingInt(TooltipStyleConfig::weight).reversed());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chatheadtooltip.admin")) {
                sender.sendMessage(miniMessage.deserialize(noPermissionMessage));
                return true;
            }

            loadConfigAndStyles();
            sender.sendMessage(miniMessage.deserialize(reloadMessage));
            return true;
        }
        
        sender.sendMessage(Component.text("Usage: /" + label + " reload"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("chatheadtooltip.admin")) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private TooltipStyleConfig getBestStyleForPlayer(Player player) {
        for (TooltipStyleConfig style : loadedStyles) {
            if (style.permission().isBlank() || player.hasPermission(style.permission())) {
                return style;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        TooltipStyleConfig activeStyle = getBestStyleForPlayer(player);

        if (activeStyle == null) return;

        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) headItem.getItemMeta();

        if (meta != null) {
            meta.setOwningPlayer(player);

            if (!activeStyle.tooltipStyleKey().isBlank()) {
                NamespacedKey styleKey = NamespacedKey.fromString(activeStyle.tooltipStyleKey());
                if (styleKey != null) {
                    meta.setTooltipStyle(styleKey);
                }
            }

            String formattedTitle = activeStyle.titleFormat().replace("<player_name>", player.getName());
            meta.displayName(miniMessage.deserialize(formattedTitle));

            List<Component> loreComponents = new ArrayList<>();
            for (String line : activeStyle.loreFormat()) {
                String parsedLine = line
                        .replace("<player_name>", player.getName())
                        .replace("<health>", String.valueOf((int) player.getHealth()))
                        .replace("<ping>", String.valueOf(player.getPing()))
                        .replace("<world>", player.getWorld().getName());
                loreComponents.add(miniMessage.deserialize(parsedLine));
            }
            meta.lore(loreComponents);

            headItem.setItemMeta(meta);
        }

        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component nameWithHover = sourceDisplayName.hoverEvent(headItem.asHoverEvent());
            return Component.text()
                    .append(nameWithHover)
                    .append(Component.text(": "))
                    .append(message)
                    .build();
        });
    }
}
