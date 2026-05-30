package fr.euphyllia.skyllia.listeners.permissions.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConvertObsidianToLavaPermissions implements PermissionModule {

    private static final Logger logger = LoggerFactory.getLogger(ConvertObsidianToLavaPermissions.class);

    private PermissionId CONVERT_OBSIDIAN_TO_LAVA;
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lavaPlaceCooldown = new ConcurrentHashMap<>();
    // 自然形成的黑曜石坐标（岩浆遇水）
    private static final Set<Location> allowedObsidian = ConcurrentHashMap.newKeySet();
    // 每个玩家是否已使用过首次还原豁免
    private final Map<UUID, Boolean> firstRestoreUsed = new ConcurrentHashMap<>();

    /**
     * 记录自然形成的黑曜石位置（岩浆遇水），供 ObsidianFormHologramListener 调用
     */
    public static void addAllowedObsidian(Location location) {
        allowedObsidian.add(location.clone());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEvent event) {
        if (!ConfigLoader.general.getIslandSettings().enableObsidianToLavaConversion()) return;
        if (!event.getAction().isRightClick()) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        final Player player = event.getPlayer();
        final Block target = event.getClickedBlock();
        if (target == null || target.getType() != Material.OBSIDIAN) return;
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem.getType() != Material.BUCKET) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        final Location location = target.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        final boolean hasPermission = SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, CONVERT_OBSIDIAN_TO_LAVA, "skyllia.player.convert_obsidian_to_lava.bypass", ConfigLoader.general.getDebugSettings().permission()
        );
        if (!hasPermission) return;

        // ---- 新增：自然形成黑曜石检查 ----
        boolean isAllowed = allowedObsidian.contains(location);
        boolean firstTime = !firstRestoreUsed.getOrDefault(player.getUniqueId(), false);

        if (!isAllowed && !firstTime) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            player.sendActionBar(ChatColor.RED + "这个黑曜石并非自然形成，无法还原");
            return;
        }

        if (firstTime) {
            firstRestoreUsed.put(player.getUniqueId(), true);
        }

        final long currentTime = System.currentTimeMillis();
        final Long lastUsed = cooldowns.get(player.getUniqueId());
        if (lastUsed != null && currentTime - lastUsed < 30000) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            player.sendActionBar(ChatColor.RED + "还原岩浆冷却中，请等待 " +
                    ((30000 - (currentTime - lastUsed)) / 1000 + 1) + " 秒");
            //logger.info("[黑曜石还原] 冷却中，玩家 {} 剩余 {} ms", player.getName(), 10000 - (currentTime - lastUsed));
            return;
        }

        target.setType(Material.AIR);
        handItem.setAmount(Math.max(0, handItem.getAmount() - 1));

        HashMap<Integer, ItemStack> leftovers = player.getInventory()
                .addItem(new ItemStack(Material.LAVA_BUCKET));
        leftovers.values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );

        player.swingHand(EquipmentSlot.HAND);
        player.playSound(target.getLocation(), org.bukkit.Sound.ITEM_BUCKET_FILL_LAVA, 1.0f, 1.0f);

        // 双重冷却
        cooldowns.put(player.getUniqueId(), currentTime);
        lavaPlaceCooldown.put(player.getUniqueId(), currentTime);

        // 还原成功后，从白名单中移除该坐标（防止重复还原同一个）
        allowedObsidian.remove(location);
    }

    // 新增：拦截桶倒空事件（岩浆桶放置）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.LAVA_BUCKET) return;

        final Player player = event.getPlayer();
        checkLavaPlaceCooldown(player, event);
    }

    // 保留作为兜底（部分情况下可能由 BlockPlaceEvent 触发，但岩浆桶放置主要走桶空事件）
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLavaPlaceBlock(final BlockPlaceEvent event) {
        if (event.getItemInHand().getType() != Material.LAVA_BUCKET) return;
        checkLavaPlaceCooldown(event.getPlayer(), event);
    }

    // 为了调试，继续保留 PlayerInteractEvent 的监听，但不再用于拦截（仅观察）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLavaPlaceInteract(final PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.LAVA_BUCKET) return;
        // 实际拦截由 PlayerBucketEmptyEvent 负责，这里仅观察
    }

    private void checkLavaPlaceCooldown(Player player, Cancellable cancellable) {
        Long lastPlace = lavaPlaceCooldown.get(player.getUniqueId());
        if (lastPlace == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long diff = now - lastPlace;

        if (diff < 1000) {
            cancellable.setCancelled(true);
        } else {
            lavaPlaceCooldown.remove(player.getUniqueId());
        }
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.CONVERT_OBSIDIAN_TO_LAVA = registry.register(new PermissionNode(
                new NamespacedKey(owner, "player.convert_obsidian_to_lava"),
                "island.permission.convert_obsidian_to_lava.name",
                "island.permission.convert_obsidian_to_lava.description"
        ));
    }
}