package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Compile-time template for the registry-aware CompoundTag API used by Paper 1.20.6. */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    public boolean lazycontainer$pending;
    public Tag lazycontainer$raw;
    public NonNullList<ItemStack> lazycontainer$items;
    public HolderLookup.Provider lazycontainer$provider;

    protected LazyContainerTemplate(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static void lazycontainer$load(CompoundTag input, NonNullList<ItemStack> items,
                                          HolderLookup.Provider provider,
                                          LazyContainerTemplate self) {
        items.clear();
        self.lazycontainer$raw = input.get("Items");
        self.lazycontainer$items = items;
        self.lazycontainer$provider = provider;
        self.lazycontainer$pending = true;
        LazyContainerRuntime.onStash();
    }

    public void lazycontainer$clear() {
        this.lazycontainer$pending = false;
        this.lazycontainer$raw = null;
        this.lazycontainer$items = null;
        this.lazycontainer$provider = null;
    }

    public void lazycontainer$ensure() {
        if (!this.lazycontainer$pending) {
            return;
        }
        this.lazycontainer$pending = false;
        Tag raw = this.lazycontainer$raw;
        NonNullList<ItemStack> items = this.lazycontainer$items;
        HolderLookup.Provider provider = this.lazycontainer$provider;
        if (raw == null) {
            this.lazycontainer$clear();
            return;
        }
        if (items == null || provider == null) {
            this.lazycontainer$pending = true;
            throw new IllegalStateException("lazy container decode context is unavailable");
        }
        try {
            CompoundTag input = new CompoundTag();
            input.put("Items", raw);
            ContainerHelper.loadAllItems(input, items, provider);
            this.lazycontainer$clear();
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            this.lazycontainer$pending = true;
            throw t;
        }
    }

    public static CompoundTag lazycontainer$save(CompoundTag output, NonNullList<ItemStack> items,
                                                 HolderLookup.Provider provider,
                                                 LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, true, provider)) {
            return output;
        }
        return ContainerHelper.saveAllItems(output, items, provider);
    }

    public static CompoundTag lazycontainer$save(CompoundTag output, NonNullList<ItemStack> items,
                                                 boolean allowEmpty, HolderLookup.Provider provider,
                                                 LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, allowEmpty, provider)) {
            return output;
        }
        return ContainerHelper.saveAllItems(output, items, allowEmpty, provider);
    }

    private boolean lazycontainer$trySaveRaw(CompoundTag output, boolean allowEmpty,
                                             HolderLookup.Provider saveProvider) {
        if (!this.lazycontainer$pending) {
            return false;
        }
        Tag raw = this.lazycontainer$raw;
        NonNullList<ItemStack> liveItems = this.lazycontainer$items;
        HolderLookup.Provider provider = this.lazycontainer$provider != null
                ? this.lazycontainer$provider : saveProvider;
        if (liveItems == null || provider == null) {
            this.lazycontainer$ensure();
            return false;
        }
        boolean canWriteRaw = raw instanceof ListTag
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw) {
            if (LazyContainerRuntime.shadow()) {
                Tag eager = this.lazycontainer$eagerItems(raw, liveItems.size(), allowEmpty, provider);
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

    private Tag lazycontainer$eagerItems(Tag raw, int size, boolean allowEmpty,
                                         HolderLookup.Provider provider) {
        CompoundTag input = new CompoundTag();
        input.put("Items", raw);
        NonNullList<ItemStack> parsed = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, parsed, provider);
        CompoundTag output = new CompoundTag();
        ContainerHelper.saveAllItems(output, parsed, allowEmpty, provider);
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
