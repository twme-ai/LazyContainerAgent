package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Compile-time template for the Mojang-mapped CompoundTag API used by Paper 1.19.4. */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    public boolean lazycontainer$pending;
    public Tag lazycontainer$raw;
    public NonNullList<ItemStack> lazycontainer$items;

    protected LazyContainerTemplate(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static void lazycontainer$load(CompoundTag input, NonNullList<ItemStack> items,
                                          LazyContainerTemplate self) {
        items.clear();
        self.lazycontainer$raw = input.get("Items");
        self.lazycontainer$items = items;
        self.lazycontainer$pending = true;
        LazyContainerRuntime.onStash();
    }

    public void lazycontainer$clear() {
        this.lazycontainer$pending = false;
        this.lazycontainer$raw = null;
        this.lazycontainer$items = null;
    }

    public void lazycontainer$ensure() {
        if (!this.lazycontainer$pending) {
            return;
        }
        this.lazycontainer$pending = false;
        Tag raw = this.lazycontainer$raw;
        NonNullList<ItemStack> items = this.lazycontainer$items;
        if (raw == null) {
            this.lazycontainer$clear();
            return;
        }
        if (items == null) {
            this.lazycontainer$pending = true;
            throw new IllegalStateException("lazy container item list is unavailable");
        }
        try {
            CompoundTag input = new CompoundTag();
            input.put("Items", raw);
            ContainerHelper.loadAllItems(input, items);
            this.lazycontainer$clear();
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            this.lazycontainer$pending = true;
            throw t;
        }
    }

    public static CompoundTag lazycontainer$save(CompoundTag output, NonNullList<ItemStack> items,
                                                 LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, true)) {
            return output;
        }
        return ContainerHelper.saveAllItems(output, items);
    }

    public static CompoundTag lazycontainer$save(CompoundTag output, NonNullList<ItemStack> items,
                                                 boolean allowEmpty, LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, allowEmpty)) {
            return output;
        }
        return ContainerHelper.saveAllItems(output, items, allowEmpty);
    }

    private boolean lazycontainer$trySaveRaw(CompoundTag output, boolean allowEmpty) {
        if (!this.lazycontainer$pending) {
            return false;
        }
        Tag raw = this.lazycontainer$raw;
        NonNullList<ItemStack> liveItems = this.lazycontainer$items;
        if (liveItems == null) {
            this.lazycontainer$ensure();
            return false;
        }
        boolean canWriteRaw = raw instanceof ListTag
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw) {
            if (LazyContainerRuntime.shadow()) {
                Tag eager = this.lazycontainer$eagerItems(raw, liveItems.size(), allowEmpty);
                if (!Objects.equals(eager, raw)) {
                    if (this.lazycontainer$sameItems(raw, eager)) {
                        LazyContainerRuntime.onBenignReorder(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        if (eager == null) {
                            output.remove("Items");
                        } else {
                            output.put("Items", eager);
                        }
                        return true;
                    }
                }
            }
            output.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        this.lazycontainer$ensure();
        return false;
    }

    private Tag lazycontainer$eagerItems(Tag raw, int size, boolean allowEmpty) {
        CompoundTag input = new CompoundTag();
        input.put("Items", raw);
        NonNullList<ItemStack> parsed = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, parsed);
        CompoundTag output = new CompoundTag();
        ContainerHelper.saveAllItems(output, parsed, allowEmpty);
        return output.get("Items");
    }

    private boolean lazycontainer$sameItems(Tag rawTag, Tag eagerTag) {
        if (!(rawTag instanceof ListTag) || !(eagerTag instanceof ListTag)) {
            return false;
        }
        ListTag raw = (ListTag) rawTag;
        ListTag eager = (ListTag) eagerTag;
        if (raw.size() != eager.size()) {
            return false;
        }
        boolean[] used = new boolean[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Tag item = raw.get(i);
            boolean found = false;
            for (int j = 0; j < eager.size(); j++) {
                if (!used[j] && item.equals(eager.get(j))) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
