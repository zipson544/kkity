package pk.ajneb97.model.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Open "Odbierz przedmioty" window of a player.
 */
public class ClaimSession {

    private final String kitName;
    private final Inventory inventory;
    // Items that did not fit in the window (more than 54 stacks)
    private final Deque<ItemStack> overflow = new ArrayDeque<>();

    public ClaimSession(String kitName, Inventory inventory) {
        this.kitName = kitName;
        this.inventory = inventory;
    }

    public String getKitName() {
        return kitName;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Deque<ItemStack> getOverflow() {
        return overflow;
    }
}
