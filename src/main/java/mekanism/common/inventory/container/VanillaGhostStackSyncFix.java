package mekanism.common.inventory.container;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;
import mekanism.common.Mekanism;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.core.NonNullList;

@ParametersAreNonnullByDefault
public class VanillaGhostStackSyncFix implements ContainerListener {

    private final List<ItemStack> lastSlots;
    private final ServerPlayer player;

    public VanillaGhostStackSyncFix(ServerPlayer player) {
        this.player = player;
        List<Slot> slots = this.player.inventoryMenu.slots;
        lastSlots = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            lastSlots.add(slot.getItem().copy());
        }
    }

    @Override
    public void slotChanged(AbstractContainerMenu container, int slotId, ItemStack stack) {
        //TODO - 1.18: Test if we can remove this as maybe the changes/introduction of a state id fixes the issues we had
        //Should always be true but validate it in case
        if (slotId < lastSlots.size()) {
            ItemStack last = lastSlots.get(slotId);
            if (container.suppressRemoteUpdates && !(container.getSlot(slotId) instanceof ResultSlot) && last.hasTag() &&
                last.getItem().getRegistryName().getNamespace().equals(Mekanism.MODID)) {
                //If the player is set to not sending slot updates to the client due to thinking that nothing changed
                // but otherwise it would and it is a mekanism item that has NBT, so could be changing similar to how
                // a jetpack changes stored gas as it is used, then we need to send the slot update packet that vanilla
                // skips to ensure no ghost stack gets created. https://github.com/mekanism/Mekanism/issues/7224 was
                // caused by a network race condition that vanilla's ignoreSlotUpdateHack is unable to account for where
                // the client sends a click packet to the server, the jetpack ticks and updates the amount of gas it has
                // stored and sends the new stack to the client, the server processes the click the client sent and tells
                // the client it agrees with the change the client thinks it made so doesn't update the slot on the client,
                // and the client gets the updated stack from the jetpack ticking and puts it back into the slot creating
                // a ghost item stack and makes the client think it still has a jetpack that it can use.
                //TODO: Eventually it might be nice to have a marker interface or something on items that could be updating
                // in the slots due to using resources, but for now the above check will do
                player.connection.send(new ClientboundContainerSetSlotPacket(container.containerId, container.incrementStateId(), slotId, stack));
            }
            //Note: We don't need to copy the stack as we are already passed a copy that will be
            // persisted in vanilla's last slot map, so we can just use the same reference. We only
            // need to use our own one because we want to update it after we do our checks instead of
            // how it is updated before our listener is called by vanilla
            lastSlots.set(slotId, stack);
        }
    }

    @Override
    public void dataChanged(AbstractContainerMenu container, int slotID, int value) {
    }
}