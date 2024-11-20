package mekanism.common.inventory.container;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.api.text.IHasTranslationKey.IHasEnumNameTranslationKey;
import mekanism.api.text.ILangEntry;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.qio.IQIOCraftingWindowHolder;
import mekanism.common.content.qio.QIOCraftingTransferHelper;
import mekanism.common.content.qio.QIOCraftingWindow;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.content.qio.SearchQueryParser;
import mekanism.common.content.qio.SearchQueryParser.ISearchQuery;
import mekanism.common.inventory.GuiComponents.IDropdownEnum;
import mekanism.common.inventory.GuiComponents.IToggleEnum;
import mekanism.common.inventory.ISlotClickHandler;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.inventory.container.slot.InsertableSlot;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.inventory.container.slot.VirtualCraftingOutputSlot;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.lib.inventory.HashedItem;
import mekanism.common.lib.inventory.HashedItem.UUIDAwareHashedItem;
import mekanism.common.network.PacketUtils;
import mekanism.common.network.to_client.qio.BulkQIOData;
import mekanism.common.network.to_server.qio.PacketQIOItemViewerSlotPlace;
import mekanism.common.network.to_server.qio.PacketQIOItemViewerSlotShiftTake;
import mekanism.common.network.to_server.qio.PacketQIOItemViewerSlotTake;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public abstract class QIOItemViewerContainer extends MekanismContainer implements ISlotClickHandler {

    public static final int SLOTS_X_MIN = 8, SLOTS_X_MAX = 16, SLOTS_Y_MIN = 2, SLOTS_Y_MAX = 48;
    public static final int SLOTS_START_Y = 43;
    private static final int DOUBLE_CLICK_TRANSFER_DURATION = SharedConstants.TICKS_PER_SECOND;

    public static int getSlotsYMax() {
        int maxY = Mth.ceil(Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.05 - 8) + 1;
        return Mth.clamp(maxY, SLOTS_Y_MIN, SLOTS_Y_MAX);
    }

    protected final Map<UUIDAwareHashedItem, ItemSlotData> cachedInventory;
    protected final IQIOCraftingWindowHolder craftingWindowHolder;
    protected final List<IScrollableSlot> searchList;
    protected final List<IScrollableSlot> itemList;

    private long cachedCountCapacity;
    private int cachedTypeCapacity;
    private long totalItems;

    private ListSortType sortType;
    private SortDirection sortDirection;
    protected String searchQuery;

    private int doubleClickTransferTicks = 0;
    private int lastSlot = -1;
    private ItemStack lastStack = ItemStack.EMPTY;
    private List<InventoryContainerSlot>[] craftingGridInputSlots;
    private final VirtualInventoryContainerSlot[][] craftingSlots = new VirtualInventoryContainerSlot[IQIOCraftingWindowHolder.MAX_CRAFTING_WINDOWS][10];

    protected QIOItemViewerContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, boolean remote, IQIOCraftingWindowHolder craftingWindowHolder,
          BulkQIOData itemData) {
        this(type, id, inv, remote, craftingWindowHolder, itemData.inventory(), itemData.countCapacity(), itemData.typeCapacity(), itemData.totalItems(), itemData.items(),
              remote ? new ArrayList<>() : Collections.emptyList(), "",
              remote ? MekanismConfig.client.qioItemViewerSortType.get() : ListSortType.NAME,
              remote ? MekanismConfig.client.qioItemViewerSortDirection.get() : SortDirection.ASCENDING,
              null
        );

        //If we are on the client, so we likely have items from the server, make sure we sort it
        if (remote && craftingWindowHolder != null) {//Crafting window holder should never be null here, but if there was an error we handle it
            updateSort();
        }
    }

    protected QIOItemViewerContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, boolean remote, IQIOCraftingWindowHolder craftingWindowHolder,
          Map<UUIDAwareHashedItem, ItemSlotData> cachedInventory, long countCapacity, int typeCapacity, long totalItems, List<IScrollableSlot> itemList,
          List<IScrollableSlot> searchList, String searchQuery, ListSortType sortType, SortDirection sortDirection, @Nullable SelectedWindowData selectedWindow) {
        super(type, id, inv);
        this.craftingWindowHolder = craftingWindowHolder;
        this.cachedInventory = cachedInventory;
        this.searchList = searchList;
        this.itemList = itemList;
        this.cachedCountCapacity = countCapacity;
        this.cachedTypeCapacity = typeCapacity;
        this.totalItems = totalItems;
        this.searchQuery = searchQuery;
        this.sortType = sortType;
        this.sortDirection = sortDirection;
        this.selectedWindow = selectedWindow;
        if (craftingWindowHolder == null) {
            //Should never happen, but in case there was an error getting the tile it may have
            Mekanism.logger.error("Error getting crafting window holder, closing.");
            closeInventory(inv.player);
            return;
        }
        if (remote) {
            //Validate the max size when we are on the client, and fix it if it is incorrect
            int maxY = getSlotsYMax();
            if (MekanismConfig.client.qioItemViewerSlotsY.get() > maxY) {
                MekanismConfig.client.qioItemViewerSlotsY.set(maxY);
                // save the updated config info
                MekanismConfig.client.save();
            }
        } else {
            craftingGridInputSlots = new List[IQIOCraftingWindowHolder.MAX_CRAFTING_WINDOWS];
        }
    }

    @Nullable
    public QIOFrequency getFrequency() {
        return craftingWindowHolder.getFrequency();
    }

    public abstract boolean shiftClickIntoFrequency();

    public abstract void toggleTargetDirection();

    /**
     * @apiNote Only used on the client
     */
    public abstract QIOItemViewerContainer recreate();

    @Override
    protected int getInventoryYOffset() {
        //Use get or default as server side these configs don't exist but the config should be just fine
        return SLOTS_START_Y + MekanismConfig.client.qioItemViewerSlotsY.getOrDefault() * 18 + 15;
    }

    @Override
    protected int getInventoryXOffset() {
        //Use get or default as server side these configs don't exist but the config should be just fine
        return super.getInventoryXOffset() + (MekanismConfig.client.qioItemViewerSlotsX.getOrDefault() - 8) * 18 / 2;
    }

    @Override
    protected void addSlots() {
        super.addSlots();
        for (QIOCraftingWindow craftingWindow : craftingWindowHolder.getCraftingWindows()) {
            byte tableIndex = craftingWindow.getWindowIndex();
            for (int slotIndex = 0; slotIndex < 9; slotIndex++) {
                addCraftingSlot(craftingWindow.getInputSlot(slotIndex), tableIndex, slotIndex);
            }
            addCraftingSlot(craftingWindow.getOutputSlot(), tableIndex, 9);
        }
    }

    private void addCraftingSlot(IInventorySlot slot, byte tableIndex, int slotIndex) {
        VirtualInventoryContainerSlot containerSlot = (VirtualInventoryContainerSlot) slot.createContainerSlot();
        craftingSlots[tableIndex][slotIndex] = containerSlot;
        addSlot(containerSlot);
    }

    public VirtualInventoryContainerSlot getCraftingWindowSlot(byte tableIndex, int slotIndex) {
        return craftingSlots[tableIndex][slotIndex];
    }

    @Override
    protected void openInventory(@NotNull Inventory inv) {
        super.openInventory(inv);
        if (!getLevel().isClientSide()) {
            QIOFrequency freq = getFrequency();
            if (freq != null) {
                freq.openItemViewer((ServerPlayer) inv.player);
            }
        }
    }

    @Override
    protected void closeInventory(@NotNull Player player) {
        super.closeInventory(player);
        if (!player.level().isClientSide()) {
            QIOFrequency freq = getFrequency();
            if (freq != null) {
                freq.closeItemViewer((ServerPlayer) player);
            }
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (doubleClickTransferTicks > 0) {
            doubleClickTransferTicks--;
        } else {
            resetTransferTracker();
        }
    }

    private void resetTransferTracker() {
        doubleClickTransferTicks = 0;
        lastSlot = -1;
        lastStack = ItemStack.EMPTY;
    }

    private void setTransferTracker(ItemStack stack, int slot) {
        doubleClickTransferTicks = DOUBLE_CLICK_TRANSFER_DURATION;
        lastSlot = slot;
        lastStack = stack;
    }

    private void doDoubleClickTransfer(Player player) {
        QIOFrequency freq = getFrequency();
        if (freq != null) {
            for (InsertableSlot slot : mainInventorySlots) {
                handleDoDoubleClickTransfer(player, slot, freq);
            }
            for (InsertableSlot slot : hotBarSlots) {
                handleDoDoubleClickTransfer(player, slot, freq);
            }
        }
    }

    private void handleDoDoubleClickTransfer(Player player, InsertableSlot slot, QIOFrequency freq) {
        if (slot.hasItem() && slot.mayPickup(player)) {
            //Note: We don't need to sanitize the slot's items as these are just InsertableSlots which have no restrictions on them on how much
            // can be extracted at once so even if they somehow have an oversized stack it will be fine
            ItemStack slotItem = slot.getItem();
            if (InventoryUtils.areItemsStackable(lastStack, slotItem)) {
                QIOItemViewerContainer.this.transferSuccess(slot, player, slotItem, freq.addItem(slotItem));
            }
        }
    }

    /**
     * Used to lazy initialize the various lists of slots for specific crafting grids
     *
     * @apiNote Only call on server
     */
    private List<InventoryContainerSlot> getCraftingGridSlots(byte selectedCraftingGrid) {
        List<InventoryContainerSlot> craftingGridSlots = craftingGridInputSlots[selectedCraftingGrid];
        if (craftingGridSlots == null) {
            //If we haven't precalculated which slots go with this crafting grid yet, do so
            craftingGridSlots = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                craftingGridSlots.add(getCraftingWindowSlot(selectedCraftingGrid, i));
            }
            craftingGridInputSlots[selectedCraftingGrid] = craftingGridSlots;
        }
        return craftingGridSlots;
    }

    @NotNull
    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int slotID) {
        Slot currentSlot = slots.get(slotID);
        if (currentSlot == null) {
            return ItemStack.EMPTY;
        }
        if (currentSlot instanceof VirtualCraftingOutputSlot virtualSlot) {
            //If we are clicking an output crafting slot, allow the slot itself to handle the transferring
            return virtualSlot.shiftClickSlot(player, hotBarSlots, mainInventorySlots);
        } else if (currentSlot instanceof InventoryContainerSlot) {
            //Otherwise, if we are an inventory container slot (crafting input slots in this case)
            // use our normal handling to attempt and transfer the contents to the player's inventory
            return super.quickMoveStack(player, slotID);
        }
        // special handling for shift-clicking into GUI
        if (!player.level().isClientSide()) {
            //Note: We don't need to sanitize the slot's items as these are just InsertableSlots which have no restrictions on them on how much
            // can be extracted at once so even if they somehow have an oversized stack it will be fine
            ItemStack slotStack = currentSlot.getItem();
            if (!shiftClickIntoFrequency()) {
                Optional<ItemStack> windowHandling = tryTransferToWindow(player, currentSlot, slotStack);
                if (windowHandling.isPresent()) {
                    return windowHandling.get();
                }
            }
            QIOFrequency frequency = getFrequency();
            if (frequency != null) {
                if (!slotStack.isEmpty()) {
                    //There is an item in the slot
                    ItemStack ret = frequency.addItem(slotStack);
                    if (slotStack.getCount() != ret.getCount()) {
                        //We were able to insert some of it
                        //Make sure that we copy it so that we aren't just pointing to the reference of it
                        setTransferTracker(slotStack.copy(), slotID);
                        return transferSuccess(currentSlot, player, slotStack, ret);
                    }
                } else {
                    if (slotID == lastSlot && !lastStack.isEmpty()) {
                        doDoubleClickTransfer(player);
                    }
                    resetTransferTracker();
                    return ItemStack.EMPTY;
                }
            }
            if (shiftClickIntoFrequency()) {
                //If we tried to shift click it into the frequency first, but weren't able to transfer it
                // either because we don't have a frequency or the frequency is full:
                // try to transfer it a potentially open window
                return tryTransferToWindow(player, currentSlot, slotStack).orElse(ItemStack.EMPTY);
            }
        }
        return ItemStack.EMPTY;
    }

    private Optional<ItemStack> tryTransferToWindow(Player player, Slot currentSlot, ItemStack slotStack) {
        byte selectedCraftingGrid = getSelectedCraftingGrid(player.getUUID());
        if (selectedCraftingGrid != -1) {
            //If the player has a crafting window open
            QIOCraftingWindow craftingWindow = getCraftingWindow(selectedCraftingGrid);
            if (!craftingWindow.isOutput(slotStack)) {
                // and the stack we are trying to transfer was not the output from the crafting window
                // as then shift clicking should be sending it into the QIO, then try transferring it
                // into the crafting window before transferring into the frequency
                ItemStack stackToInsert = slotStack;
                List<InventoryContainerSlot> craftingGridSlots = getCraftingGridSlots(selectedCraftingGrid);
                SelectedWindowData windowData = craftingWindow.getWindowData();
                //Start by trying to stack it with other things and if that fails try to insert it into empty slots
                stackToInsert = insertItem(craftingGridSlots, stackToInsert, windowData);
                if (stackToInsert.getCount() != slotStack.getCount()) {
                    //If something changed, decrease the stack by the amount we inserted,
                    // and return it as a new stack for what is now in the slot
                    return Optional.of(transferSuccess(currentSlot, player, slotStack, stackToInsert));
                }
                //Otherwise, if nothing changed, try to transfer into the QIO Frequency
            }
        }
        return Optional.empty();
    }

    public void handleUpdate(Object2LongMap<UUIDAwareHashedItem> itemMap, long countCapacity, int typeCapacity) {
        cachedCountCapacity = countCapacity;
        cachedTypeCapacity = typeCapacity;
        if (itemMap.isEmpty()) {
            //No items need updating, we just changed the counts/capacities, in general this should never be the case, but in case it is
            // just short circuit a lot of logic
            return;
        }
        boolean needsSort = sortType.usesCount();
        for (Object2LongMap.Entry<UUIDAwareHashedItem> entry : itemMap.object2LongEntrySet()) {
            UUIDAwareHashedItem itemKey = entry.getKey();
            long value = entry.getLongValue();
            if (value == 0) {
                ItemSlotData oldData = cachedInventory.remove(itemKey);
                if (oldData != null) {
                    //If we did in fact have old data stored, remove the item from the stored total count
                    totalItems -= oldData.count();
                    // and remove the item from the list of items we are tracking
                    // Note: Implementation detail is that we use a ReferenceArrayList in BulkQIOData#fromPacket to ensure that when removing
                    // we only need to do reference equality instead of object equality
                    //TODO: Can we somehow make removing more efficient by taking advantage of the fact that itemList is sorted?
                    itemList.remove(oldData);
                    //Mark that we have some items that changed and it isn't just counts that changed
                    needsSort = true;
                }
            } else {
                ItemSlotData slotData = cachedInventory.get(itemKey);
                if (slotData == null) {
                    //If it is a new item, add the amount to the total items, and start tracking it
                    totalItems += value;
                    slotData = new ItemSlotData(itemKey, value);
                    itemList.add(slotData);
                    cachedInventory.put(itemKey, slotData);
                    //Mark that we have some items that changed and it isn't just counts that changed
                    needsSort = true;
                } else {
                    //If an existing item is updated, update the stored amount by the change in quantity
                    totalItems += value - slotData.count();
                    slotData.count = value;
                }
            }
        }
        if (needsSort) {
            //Note: We only need to bother resorting the lists and recalculating the sorted searches if an item was added or removed
            // or if the sort method we have selected is affected at some level by the stored count
            updateSort();
        }
    }

    public void handleKill() {
        cachedInventory.clear();
        searchList.clear();
        itemList.clear();
        searchQuery = "";
    }

    public QIOCraftingTransferHelper getTransferHelper(Player player, QIOCraftingWindow craftingWindow) {
        return new QIOCraftingTransferHelper(cachedInventory.values(), hotBarSlots, mainInventorySlots, craftingWindow, player);
    }

    /**
     * @apiNote Only call this client side
     */
    public void setSortDirection(SortDirection sortDirection) {
        this.sortDirection = sortDirection;
        MekanismConfig.client.qioItemViewerSortDirection.set(sortDirection);
        MekanismConfig.client.save();
        updateSort();
    }

    public SortDirection getSortDirection() {
        return sortDirection;
    }

    /**
     * @apiNote Only call this client side
     */
    public void setSortType(ListSortType sortType) {
        this.sortType = sortType;
        MekanismConfig.client.qioItemViewerSortType.set(sortType);
        MekanismConfig.client.save();
        updateSort();
    }

    public ListSortType getSortType() {
        return sortType;
    }

    @NotNull
    public List<IScrollableSlot> getQIOItemList() {
        return searchQuery.isEmpty() ? itemList : searchList;
    }

    public long getCountCapacity() {
        return cachedCountCapacity;
    }

    public int getTypeCapacity() {
        return cachedTypeCapacity;
    }

    public long getTotalItems() {
        return totalItems;
    }

    public int getTotalTypes() {
        return itemList.size();
    }

    public byte getSelectedCraftingGrid() {
        return getSelectedCraftingGrid(getSelectedWindow());
    }

    /**
     * @apiNote Only call on server
     */
    public byte getSelectedCraftingGrid(UUID player) {
        return getSelectedCraftingGrid(getSelectedWindow(player));
    }

    private byte getSelectedCraftingGrid(@Nullable SelectedWindowData selectedWindow) {
        if (selectedWindow != null && selectedWindow.type == WindowType.CRAFTING) {
            return selectedWindow.extraData;
        }
        return (byte) -1;
    }

    public QIOCraftingWindow getCraftingWindow(int selectedCraftingGrid) {
        if (selectedCraftingGrid < 0 || selectedCraftingGrid >= IQIOCraftingWindowHolder.MAX_CRAFTING_WINDOWS) {
            throw new IllegalArgumentException("Selected crafting grid not in range.");
        }
        return craftingWindowHolder.getCraftingWindows()[selectedCraftingGrid];
    }

    /**
     * @apiNote Only call on server
     */
    public ItemStack insertIntoPlayerInventory(UUID player, ItemStack stack) {
        SelectedWindowData selectedWindow = getSelectedWindow(player);
        stack = insertItem(hotBarSlots, stack, true, selectedWindow);
        stack = insertItem(mainInventorySlots, stack, true, selectedWindow);
        stack = insertItem(hotBarSlots, stack, false, selectedWindow);
        stack = insertItem(mainInventorySlots, stack, false, selectedWindow);
        return stack;
    }

    /**
     * @apiNote Only call on server
     */
    public ItemStack simulateInsertIntoPlayerInventory(UUID player, ItemStack stack) {
        SelectedWindowData selectedWindow = getSelectedWindow(player);
        stack = insertItemCheckAll(hotBarSlots, stack, selectedWindow, Action.SIMULATE);
        stack = insertItemCheckAll(mainInventorySlots, stack, selectedWindow, Action.SIMULATE);
        return stack;
    }

    private void updateSort() {
        sortType.sort(itemList, sortDirection);
        //TODO: Would it be easier to add/remove changed things that no longer match and then just run the sort on the search list as well?
        // Or in cases where we are just doing a resort without the search query changing, running a sort on the search list as well?
        // This might be beneficial at the very least in cases where the search list is small, and the list of total items is large
        //Note: Update the search as well because it is based on the sorted list so that it displays matches in sorted order
        updateSearch(getLevel(), searchQuery, false);
    }

    public void updateSearch(@Nullable Level level, String queryText, boolean skipSameQuery) {
        // searches should only be updated on the client-side
        if (level == null || !level.isClientSide()) {
            return;
        } else if (skipSameQuery && searchQuery.equals(queryText)) {
            //Short circuit and skip updating the search if we already have the results
            return;
        }
        //TODO: Realistically we may want to be caching the ISearchQuery rather than or in addition to the query text?
        searchQuery = queryText;
        searchList.clear();
        if (!itemList.isEmpty() && !searchQuery.isEmpty()) {
            //TODO: Improve how we cache to allow for some form of incremental updating based on the search text changing?
            ISearchQuery query = SearchQueryParser.parse(searchQuery);
            for (IScrollableSlot slot : itemList) {
                if (query.test(level, inv.player, slot.getInternalStack())) {
                    searchList.add(slot);
                }
            }
        }
    }

    @Override
    public void onClick(Supplier<@Nullable IScrollableSlot> slotProvider, int button, boolean hasShiftDown, ItemStack heldItem) {
        if (hasShiftDown) {
            IScrollableSlot slot = slotProvider.get();
            if (slot != null) {
                PacketUtils.sendToServer(new PacketQIOItemViewerSlotShiftTake(slot.itemUUID()));
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (heldItem.isEmpty()) {
                IScrollableSlot slot = slotProvider.get();
                if (slot != null) {
                    int maxStackSize = Math.min(MathUtils.clampToInt(slot.count()), slot.item().getMaxStackSize());
                    //Left click -> as much as possible, right click -> half of a stack, middle click -> 1
                    //Cap it out at the max stack size of the item, but otherwise try to take the desired amount (taking at least one if it is a single item)
                    int toTake;
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        toTake = maxStackSize;
                    } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                        toTake = 1;
                    } else {
                        toTake = Math.max(1, maxStackSize / 2);
                    }
                    PacketUtils.sendToServer(new PacketQIOItemViewerSlotTake(slot.itemUUID(), toTake));
                }
            } else {
                //middle click -> add to current stack if over slot and stackable, else normal storage functionality
                IScrollableSlot slot;
                if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && (slot = slotProvider.get()) != null && InventoryUtils.areItemsStackable(heldItem, slot.getInternalStack())) {
                    PacketUtils.sendToServer(new PacketQIOItemViewerSlotTake(slot.itemUUID(), 1));
                } else {
                    //Left click -> all held, right click -> single item
                    int toAdd = button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? heldItem.getCount() : 1;
                    PacketUtils.sendToServer(new PacketQIOItemViewerSlotPlace(toAdd));
                }
            }
        }
    }

    public static final class ItemSlotData implements IScrollableSlot {

        private final UUIDAwareHashedItem item;
        private long count;

        public ItemSlotData(UUIDAwareHashedItem item, long count) {
            this.item = item;
            this.count = count;
        }

        @Override
        public HashedItem asRawHashedItem() {
            return item.asRawHashedItem();
        }

        @Override
        public UUIDAwareHashedItem item() {
            return item;
        }

        @Override
        public UUID itemUUID() {
            return item.getUUID();
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            ItemSlotData other = (ItemSlotData) obj;
            return this.count == other.count && this.item.equals(other.item);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, count);
        }
    }

    public enum SortDirection implements IToggleEnum<SortDirection>, IHasEnumNameTranslationKey {
        ASCENDING(MekanismUtils.getResource(ResourceType.GUI, "arrow_up.png"), MekanismLang.LIST_SORT_ASCENDING, MekanismLang.LIST_SORT_ASCENDING_DESC),
        DESCENDING(MekanismUtils.getResource(ResourceType.GUI, "arrow_down.png"), MekanismLang.LIST_SORT_DESCENDING, MekanismLang.LIST_SORT_DESCENDING_DESC);

        private final ResourceLocation icon;
        private final ILangEntry name;
        private final ILangEntry tooltip;

        SortDirection(ResourceLocation icon, ILangEntry name, ILangEntry tooltip) {
            this.icon = icon;
            this.name = name;
            this.tooltip = tooltip;
        }

        @Override
        public ResourceLocation getIcon() {
            return icon;
        }

        @Override
        public Component getTooltip() {
            return tooltip.translate();
        }

        public boolean isAscending() {
            return this == ASCENDING;
        }

        @NotNull
        @Override
        public String getTranslationKey() {
            return name.getTranslationKey();
        }
    }

    public enum ListSortType implements IDropdownEnum<ListSortType>, TranslatableEnum {
        NAME(MekanismLang.LIST_SORT_NAME, MekanismLang.LIST_SORT_NAME_DESC, false, Comparator.comparing(IScrollableSlot::getDisplayName)),
        SIZE(MekanismLang.LIST_SORT_COUNT, MekanismLang.LIST_SORT_COUNT_DESC, true,
              Comparator.comparingLong(IScrollableSlot::count).thenComparing(IScrollableSlot::getDisplayName),
              Comparator.comparingLong(IScrollableSlot::count).reversed().thenComparing(IScrollableSlot::getDisplayName)),
        MOD(MekanismLang.LIST_SORT_MOD, MekanismLang.LIST_SORT_MOD_DESC, false,
              Comparator.comparing(IScrollableSlot::getModID).thenComparing(IScrollableSlot::getDisplayName),
              Comparator.comparing(IScrollableSlot::getModID).reversed().thenComparing(IScrollableSlot::getDisplayName)),
        REGISTRY_NAME(MekanismLang.LIST_SORT_REGISTRY_NAME, MekanismLang.LIST_SORT_REGISTRY_NAME_DESC, true,
              Comparator.comparing(IScrollableSlot::getRegistryName, ResourceLocation::compareNamespaced).thenComparingLong(IScrollableSlot::count),
              Comparator.comparing(IScrollableSlot::getRegistryName, ResourceLocation::compareNamespaced).reversed().thenComparingLong(IScrollableSlot::count));

        private final ILangEntry name;
        private final ILangEntry tooltip;
        private final boolean usesCount;
        private final Comparator<IScrollableSlot> ascendingComparator;
        private final Comparator<IScrollableSlot> descendingComparator;

        ListSortType(ILangEntry name, ILangEntry tooltip, boolean usesCount, Comparator<IScrollableSlot> ascendingComparator) {
            this(name, tooltip, usesCount, ascendingComparator, ascendingComparator.reversed());
        }

        ListSortType(ILangEntry name, ILangEntry tooltip, boolean usesCount, Comparator<IScrollableSlot> ascendingComparator,
              Comparator<IScrollableSlot> descendingComparator) {
            this.name = name;
            this.tooltip = tooltip;
            this.usesCount = usesCount;
            this.ascendingComparator = ascendingComparator;
            this.descendingComparator = descendingComparator;
        }

        public void sort(List<IScrollableSlot> list, SortDirection direction) {
            if (!list.isEmpty()) {
                list.sort(direction.isAscending() ? ascendingComparator : descendingComparator);
            }
        }

        /**
         * @return true if the sort type has any level of sorting based on count
         */
        public boolean usesCount() {
            return usesCount;
        }

        @Override
        public Component getTooltip() {
            return tooltip.translate();
        }

        @Override
        public Component getShortName() {
            return name.translate();
        }

        @NotNull
        @Override
        public Component getTranslatedName() {
            return getShortName();
        }
    }
}