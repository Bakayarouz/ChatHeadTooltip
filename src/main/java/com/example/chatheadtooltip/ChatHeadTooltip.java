package com.example.chatheadtooltip;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
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
    private final GsonComponentSerializer gsonSerializer = GsonComponentSerializer.gson();
    
    private boolean papiEnabled = false;
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
        
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            papiEnabled = true;
            getLogger().info("Successfully hooked into PlaceholderAPI!");
        }

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
                String title = styleSection.getString("title", "<player_head> <yellow><player_name>");
                List<String> lore = styleSection.getStringList("lore");

                loadedStyles.add(new TooltipStyleConfig(permission, weight, tooltipStyle, title, lore));
            }
        }

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

    /**
     * Constructs vanilla's native player text component: {"player": {"name": "..."}}
     */
    private Component createPlayerHeadComponent(Player player) {
        String json = "{\"player\":{\"name\":\"" + player.getName() + "\"}}";
        return gsonSerializer.deserialize(json);
    }

    private Component parseAndBuildComponent(String text, Player player) {
        double maxHealth = 20.0;
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        }

        // 1. Internal text replacements
        String parsed = text
                .replace("<player_name>", player.getName())
                .replace("<health>", String.valueOf((int) player.getHealth()))
                .replace("<max_health>", String.valueOf((int) maxHealth))
                .replace("<ping>", String.valueOf(player.getPing()))
                .replace("<world>", player.getWorld().getName());

        // 2. Parse PlaceholderAPI placeholders if present
        if (papiEnabled) {
            parsed = PlaceholderAPI.setPlaceholders(player, parsed);
        }

        // 3. Replace <player_head> with the native vanilla player JSON component
        if (parsed.contains("<player_head>")) {
            String[] parts = parsed.split("<player_head>", -1);
            Component result = Component.empty();
            Component headComponent = createPlayerHeadComponent(player);

            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    result = result.append(miniMessage.deserialize(parts[i]));
                }
                if (i < parts.length - 1) {
                    result = result.append(headComponent);
                }
            }
            return result;
        }

        return miniMessage.deserialize(parsed);
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

            // Apply custom 9-slice tooltip style component
            if (!activeStyle.tooltipStyleKey().isBlank()) {
                NamespacedKey styleKey = NamespacedKey.fromString(activeStyle.tooltipStyleKey());
                if (styleKey != null) {
                    meta.setTooltipStyle(styleKey);
                }
            }

            // Title
            meta.displayName(parseAndBuildComponent(activeStyle.titleFormat(), player));

            // Lore
            List<Component> loreComponents = new ArrayList<>();
            for (String line : activeStyle.loreFormat()) {
                loreComponents.add(parseAndBuildComponent(line, player));
            }
            meta.lore(loreComponents);

            headItem.setItemMeta(meta);
        }

        // Attach hover event to chat renderer
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
