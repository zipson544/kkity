package pk.ajneb97.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import pk.ajneb97.PlayerKits2;
import pk.ajneb97.managers.ClaimManager;
import pk.ajneb97.model.inventory.ClaimSession;
import pk.ajneb97.utils.InventoryUtils;

public class ClaimListener implements Listener {

    private final PlayerKits2 plugin;

    public ClaimListener(PlayerKits2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ClaimManager manager = plugin.getClaimManager();
        ClaimSession session = manager.getSession(player);
        if (session == null) {
            return;
        }
        Inventory top = InventoryUtils.getTopInventory(event);
        if (top == null || !top.equals(session.getInventory())) {
            return;
        }

        manager.scheduleCompletionCheck(player);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            // Click outside the window: would drop the cursor item on the ground
            event.setCancelled(true);
            return;
        }

        InventoryAction action = event.getAction();
        if (clicked.equals(top)) {
            ItemStack cursor = event.getCursor();
            boolean cursorEmpty = cursor == null || cursor.getType().equals(Material.AIR);
            boolean allowed = false;
            if (cursorEmpty) {
                switch (action) {
                    case PICKUP_ALL:
                    case PICKUP_HALF:
                    case PICKUP_SOME:
                    case PICKUP_ONE:
                    case MOVE_TO_OTHER_INVENTORY:
                        allowed = true;
                        break;
                    case HOTBAR_SWAP:
                    case HOTBAR_MOVE_AND_READD:
                        int button = event.getHotbarButton();
                        if (button >= 0) {
                            ItemStack target = player.getInventory().getItem(button);
                            allowed = target == null || target.getType().equals(Material.AIR);
                        }
                        break;
                    default:
                        break;
                }
            }
            if (!allowed) {
                event.setCancelled(true);
            }
        } else {
            // Player's own inventory: block everything that could move items into the window
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ClaimSession session = plugin.getClaimManager().getSession(player);
        if (session == null) {
            return;
        }
        Inventory top = InventoryUtils.getTopInventory(event);
        if (top == null || !top.equals(session.getInventory())) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        plugin.getClaimManager().handleClose((Player) event.getPlayer(), event.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getClaimManager().handleLeave(event.getPlayer());
    }
}
