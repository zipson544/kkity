package pk.ajneb97.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pk.ajneb97.PlayerKits2;
import pk.ajneb97.libs.titles.TitleAPI;
import pk.ajneb97.model.Kit;
import pk.ajneb97.model.internal.GiveKitInstructions;
import pk.ajneb97.model.internal.PlayerKitsMessageResult;
import pk.ajneb97.model.inventory.ClaimSession;
import pk.ajneb97.model.inventory.InventoryPlayer;
import pk.ajneb97.model.item.KitItem;
import pk.ajneb97.utils.ItemUtils;
import pk.ajneb97.utils.MiniMessageUtils;
import pk.ajneb97.utils.PlayerUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Kit page button (green / orange) + "Odbierz przedmioty" window where items are taken manually.
 * The cooldown starts only after ALL items were taken.
 */
public class ClaimManager {

    public static final String TAG_BUTTON = "playerkits_claim_button";
    public static final String TAG_BUTTON_STATUS = "playerkits_claim_status";

    private final PlayerKits2 plugin;
    private final Map<UUID, ClaimSession> sessions = new HashMap<>();
    // player -> kit -> items that were not taken yet
    private final Map<UUID, Map<String, List<ItemStack>>> pending = new HashMap<>();
    private File file;

    public ClaimManager(PlayerKits2 plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ messages

    private String msg(String key, String def) {
        FileConfiguration c = plugin.getConfigsManager().getMessagesConfigManager().getConfig();
        String v = c.getString(key);
        return v != null ? v : def;
    }

    private List<String> msgList(String key, List<String> def) {
        FileConfiguration c = plugin.getConfigsManager().getMessagesConfigManager().getConfig();
        if (c.contains(key)) {
            return c.getStringList(key);
        }
        return def;
    }

    // ------------------------------------------------------------------ pending (persisted)

    public void load() {
        file = new File(plugin.getDataFolder(), "claims.yml");
        pending.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("claims");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String path = "claims." + key;
            String uuidString = config.getString(path + ".uuid");
            String kit = config.getString(path + ".kit");
            if (uuidString == null || kit == null) {
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            List<?> raw = config.getList(path + ".items");
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof ItemStack) {
                        items.add((ItemStack) o);
                    }
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(uuidString);
                pending.computeIfAbsent(uuid, k -> new HashMap<>()).put(kit, items);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void savePending() {
        if (file == null) {
            file = new File(plugin.getDataFolder(), "claims.yml");
        }
        FileConfiguration config = new YamlConfiguration();
        int index = 0;
        for (Map.Entry<UUID, Map<String, List<ItemStack>>> entry : pending.entrySet()) {
            for (Map.Entry<String, List<ItemStack>> kitEntry : entry.getValue().entrySet()) {
                if (kitEntry.getValue().isEmpty()) {
                    continue;
                }
                String path = "claims." + index;
                config.set(path + ".uuid", entry.getKey().toString());
                config.set(path + ".kit", kitEntry.getKey());
                config.set(path + ".items", kitEntry.getValue());
                index++;
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private List<ItemStack> getPending(Player player, String kitName) {
        Map<String, List<ItemStack>> map = pending.get(player.getUniqueId());
        if (map == null) {
            return null;
        }
        List<ItemStack> list = map.get(kitName);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list;
    }

    private boolean hasPending(Player player, String kitName) {
        return getPending(player, kitName) != null;
    }

    private void setPending(Player player, String kitName, List<ItemStack> items) {
        pending.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(kitName, items);
        savePending();
    }

    private void clearPending(Player player, String kitName) {
        Map<String, List<ItemStack>> map = pending.get(player.getUniqueId());
        if (map != null) {
            map.remove(kitName);
            if (map.isEmpty()) {
                pending.remove(player.getUniqueId());
            }
            savePending();
        }
    }

    // ------------------------------------------------------------------ button on the kit page

    private static class ButtonState {
        String status;
        String time = "";
        String error;

        ButtonState(String status, String time, String error) {
            this.status = status;
            this.time = time;
            this.error = error;
        }
    }

    private ButtonState computeState(Player player, Kit kit) {
        PlayerDataManager pdm = plugin.getPlayerDataManager();
        if (hasPending(player, kit.getName())) {
            return new ButtonState("pending", "", null);
        }
        if (!kit.playerHasPermission(player)) {
            return new ButtonState("blocked", "", msg("kitNoPermissions", "&cNie masz uprawnień do odebrania tego zestawu."));
        }
        boolean admin = PlayerUtils.isPlayerKitsAdmin(player);
        if (kit.isOneTime() && !admin && !PlayerUtils.hasOneTimeBypassPermission(player)
                && pdm.isKitOneTime(player, kit.getName())) {
            return new ButtonState("blocked", "", msg("oneTimeError", "&cNie możesz ponownie odebrać tego zestawu."));
        }
        if (kit.getCooldown() != 0 && !admin && !PlayerUtils.hasCooldownBypassPermission(player)) {
            String time = pdm.getKitCooldownString(pdm.getKitCooldown(player, kit.getName()));
            if (!time.isEmpty()) {
                return new ButtonState("cooldown", time, null);
            }
        }
        return new ButtonState("ready", "", null);
    }

    private String color(String text) {
        return MessagesManager.getLegacyColoredMessage(text);
    }

    public ItemStack createClaimButton(Player player, String kitName) {
        Kit kit = plugin.getKitsManager().getKitByName(kitName);
        ButtonState state;
        if (kit == null) {
            state = new ButtonState("blocked", "", msg("kitDoesNotExists", "&cZestaw %kit% nie istnieje.").replace("%kit%", kitName));
        } else {
            state = computeState(player, kit);
        }

        Material material = Material.LIME_DYE;
        List<String> lore;
        switch (state.status) {
            case "cooldown":
                material = Material.ORANGE_DYE;
                lore = new ArrayList<>();
                for (String line : msgList("claimButtonCooldownLore",
                        Collections.singletonList("&7» Następny raz możesz odebrać za &f%time%&7!"))) {
                    lore.add(line.replace("%time%", state.time));
                }
                break;
            case "blocked":
                material = Material.RED_DYE;
                lore = Collections.singletonList("&7» " + state.error);
                break;
            case "pending":
                lore = msgList("claimButtonPendingLore",
                        Collections.singletonList("&7» &eMasz jeszcze nieodebrane przedmioty z tego zestawu!"));
                break;
            default:
                lore = msgList("claimButtonReadyLore",
                        Collections.singletonList("&7» &aKliknij, aby odebrać przedmioty!"));
                break;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(msg("claimButtonName", "&e&lOdbierz przedmioty z zestawu!")));
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) {
            coloredLore.add(color(line));
        }
        meta.setLore(coloredLore);
        item.setItemMeta(meta);

        item = ItemUtils.setTagStringItem(plugin, item, TAG_BUTTON, kitName);
        item = ItemUtils.setTagStringItem(plugin, item, TAG_BUTTON_STATUS, state.status);
        return item;
    }

    /**
     * Called every second by the inventory update task.
     */
    public void updateClaimButton(Player player, Inventory inv, int slot, ItemStack current, String kitName) {
        ItemStack fresh = createClaimButton(player, kitName);
        String oldStatus = ItemUtils.getTagStringItem(plugin, current, TAG_BUTTON_STATUS);
        String newStatus = ItemUtils.getTagStringItem(plugin, fresh, TAG_BUTTON_STATUS);
        if (newStatus != null && newStatus.equals(oldStatus) && current.getType().equals(fresh.getType())) {
            ItemMeta meta = current.getItemMeta();
            ItemMeta freshMeta = fresh.getItemMeta();
            meta.setDisplayName(freshMeta.getDisplayName());
            meta.setLore(freshMeta.getLore());
            current.setItemMeta(meta);
        } else {
            inv.setItem(slot, fresh);
        }
    }

    // ------------------------------------------------------------------ starting a claim

    public void clickClaimButton(InventoryPlayer inventoryPlayer, String kitName) {
        Player player = inventoryPlayer.getPlayer();
        Kit kit = plugin.getKitsManager().getKitByName(kitName);
        if (kit == null) {
            return;
        }
        PlayerKitsMessageResult result = startClaim(player, kit, new GiveKitInstructions());
        if (result.isError()) {
            plugin.getMessagesManager().sendMessage(player, result.getMessage(), true);
            return;
        }
        if (result.isProceedToBuy()) {
            inventoryPlayer.setPreviousInventoryName(inventoryPlayer.getInventoryName());
            inventoryPlayer.setInventoryName("buy_requirements_inventory");
            inventoryPlayer.setKitName(kitName);
            plugin.getInventoryManager().openInventory(inventoryPlayer);
        }
    }

    /**
     * Validates the kit and opens the claim window. Returns an error result,
     * a result with proceedToBuy (nothing was opened) or a plain success (window opened).
     */
    public PlayerKitsMessageResult startClaim(Player player, Kit kit, GiveKitInstructions instructions) {
        String kitName = kit.getName();
        KitsManager kitsManager = plugin.getKitsManager();

        List<ItemStack> items = getPending(player, kitName);
        if (items == null) {
            PlayerKitsMessageResult result = kitsManager.checkKitClaim(player, kit, instructions);
            if (result.isError() || result.isProceedToBuy()) {
                return result;
            }

            // Price / buy actions are applied now, cooldown only after taking everything
            kitsManager.applyKitBuyProperties(player, kit, instructions);

            items = buildItems(player, kit);
            if (items.isEmpty()) {
                completeClaim(player, kitName);
                return PlayerKitsMessageResult.success();
            }
            setPending(player, kitName, items);
        }

        openWindow(player, kitName, items);
        return PlayerKitsMessageResult.success();
    }

    private List<ItemStack> buildItems(Player player, Kit kit) {
        KitItemManager kitItemManager = plugin.getKitItemManager();
        List<ItemStack> list = new ArrayList<>();
        for (KitItem kitItem : kit.getItems()) {
            ItemStack item = kitItemManager.createItemFromKitItem(kitItem, player, kit);
            if (item != null && !item.getType().equals(Material.AIR)) {
                list.add(item);
            }
        }
        return list;
    }

    private void openWindow(Player player, String kitName, List<ItemStack> items) {
        int size = Math.min(54, ((items.size() + 8) / 9) * 9);
        if (size < 9) {
            size = 9;
        }
        String title = msg("claimWindowTitle", "Odbierz przedmioty");
        Inventory inv;
        if (plugin.getConfigsManager().getMainConfigManager().isUseMiniMessage()) {
            inv = MiniMessageUtils.createInventory(size, title);
        } else {
            inv = Bukkit.createInventory(null, size, color(title));
        }

        ClaimSession session = new ClaimSession(kitName, inv);
        int slot = 0;
        for (ItemStack item : items) {
            if (slot < size) {
                inv.setItem(slot, item.clone());
                slot++;
            } else {
                session.getOverflow().add(item.clone());
            }
        }

        player.openInventory(inv);
        sessions.put(player.getUniqueId(), session);
    }

    // ------------------------------------------------------------------ session handling

    public ClaimSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    private boolean isEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                return false;
            }
        }
        return true;
    }

    private List<ItemStack> collectRemaining(ClaimSession session) {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : session.getInventory().getContents()) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                remaining.add(item.clone());
            }
        }
        remaining.addAll(session.getOverflow());
        return remaining;
    }

    private void refillFromOverflow(ClaimSession session) {
        Inventory inv = session.getInventory();
        while (!session.getOverflow().isEmpty()) {
            int slot = inv.firstEmpty();
            if (slot == -1) {
                return;
            }
            inv.setItem(slot, session.getOverflow().poll());
        }
    }

    /**
     * Runs one tick after every click in the window.
     */
    public void scheduleCompletionCheck(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> checkCompletion(player));
    }

    private void checkCompletion(Player player) {
        ClaimSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        refillFromOverflow(session);
        if (!isEmpty(session.getInventory()) || !session.getOverflow().isEmpty()) {
            return;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().equals(Material.AIR)) {
            // Last item is still on the cursor, wait until the player puts it in the inventory
            return;
        }
        sessions.remove(player.getUniqueId());
        completeClaim(player, session.getKitName());
        player.closeInventory();
    }

    public void handleClose(Player player, Inventory closed) {
        ClaimSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.getInventory().equals(closed)) {
            return;
        }
        sessions.remove(player.getUniqueId());
        List<ItemStack> remaining = collectRemaining(session);
        if (remaining.isEmpty()) {
            completeClaim(player, session.getKitName());
        } else {
            setPending(player, session.getKitName(), remaining);
        }
    }

    public void handleLeave(Player player) {
        ClaimSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        List<ItemStack> remaining = collectRemaining(session);
        if (remaining.isEmpty()) {
            completeClaim(player, session.getKitName());
        } else {
            setPending(player, session.getKitName(), remaining);
        }
    }

    public void saveAll() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            ClaimSession session = sessions.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (session == null || player == null) {
                continue;
            }
            List<ItemStack> remaining = collectRemaining(session);
            if (remaining.isEmpty()) {
                clearPending(player, session.getKitName());
            } else {
                pending.computeIfAbsent(uuid, k -> new HashMap<>()).put(session.getKitName(), remaining);
            }
        }
        savePending();
    }

    private void completeClaim(Player player, String kitName) {
        clearPending(player, kitName);
        Kit kit = plugin.getKitsManager().getKitByName(kitName);
        if (kit != null) {
            plugin.getKitsManager().sendKitActions(kit.getClaimActions(), player, true);
            plugin.getKitsManager().sendKitActions(kit.getClaimActions(), player, false);
            // Cooldown / one time start only now
            plugin.getKitsManager().applyKitUsageProperties(player, kit, new GiveKitInstructions());
        }

        String text = msg("allItemsClaimed", "&a&lOdebrano wszystkie przedmioty!");
        TitleAPI.sendTitle(player, 5, 40, 10, "", color(text));
    }
}
