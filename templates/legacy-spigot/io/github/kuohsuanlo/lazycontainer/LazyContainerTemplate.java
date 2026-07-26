package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;

import net.minecraft.core.BlockPosition;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.ContainerUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.TileEntityContainer;
import net.minecraft.world.level.block.entity.TileEntityTypes;
import net.minecraft.world.level.block.state.IBlockData;

/** Compile-time template for the default Spigot-mapped Paper 1.19.4 artifact. */
public abstract class LazyContainerTemplate extends TileEntityContainer {

    public boolean lazycontainer$pending;
    public NBTBase lazycontainer$raw;
    public NonNullList<ItemStack> lazycontainer$items;

    protected LazyContainerTemplate(TileEntityTypes<?> type, BlockPosition pos, IBlockData state) {
        super(type, pos, state);
    }

    public static void lazycontainer$load(NBTTagCompound input, NonNullList<ItemStack> items,
                                          LazyContainerTemplate self) {
        items.clear();
        self.lazycontainer$raw = input.c("Items");
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
        NBTBase raw = this.lazycontainer$raw;
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
            NBTTagCompound input = new NBTTagCompound();
            input.a("Items", raw);
            ContainerUtil.b(input, items);
            this.lazycontainer$clear();
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            this.lazycontainer$pending = true;
            throw t;
        }
    }

    public static NBTTagCompound lazycontainer$save(NBTTagCompound output,
                                                    NonNullList<ItemStack> items,
                                                    LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, true)) {
            return output;
        }
        return ContainerUtil.a(output, items);
    }

    public static NBTTagCompound lazycontainer$save(NBTTagCompound output,
                                                    NonNullList<ItemStack> items,
                                                    boolean allowEmpty,
                                                    LazyContainerTemplate self) {
        if (self.lazycontainer$trySaveRaw(output, allowEmpty)) {
            return output;
        }
        return ContainerUtil.a(output, items, allowEmpty);
    }

    private boolean lazycontainer$trySaveRaw(NBTTagCompound output, boolean allowEmpty) {
        if (!this.lazycontainer$pending) {
            return false;
        }
        NBTBase raw = this.lazycontainer$raw;
        NonNullList<ItemStack> liveItems = this.lazycontainer$items;
        if (liveItems == null) {
            this.lazycontainer$ensure();
            return false;
        }
        boolean canWriteRaw = raw instanceof NBTTagList
                && !(!allowEmpty && ((NBTTagList) raw).isEmpty());
        if (canWriteRaw) {
            if (LazyContainerRuntime.shadow()) {
                NBTBase eager = this.lazycontainer$eagerItems(raw, liveItems.size(), allowEmpty);
                if (!Objects.equals(eager, raw)) {
                    if (this.lazycontainer$sameItems(raw, eager)) {
                        LazyContainerRuntime.onBenignReorder(String.valueOf(this.p()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(this.p()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        if (eager == null) {
                            output.r("Items");
                        } else {
                            output.a("Items", eager);
                        }
                        return true;
                    }
                }
            }
            output.a("Items", raw.d());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        this.lazycontainer$ensure();
        return false;
    }

    private NBTBase lazycontainer$eagerItems(NBTBase raw, int size, boolean allowEmpty) {
        NBTTagCompound input = new NBTTagCompound();
        input.a("Items", raw);
        NonNullList<ItemStack> parsed = NonNullList.a(size, ItemStack.b);
        ContainerUtil.b(input, parsed);
        NBTTagCompound output = new NBTTagCompound();
        ContainerUtil.a(output, parsed, allowEmpty);
        return output.c("Items");
    }

    private boolean lazycontainer$sameItems(NBTBase rawTag, NBTBase eagerTag) {
        if (!(rawTag instanceof NBTTagList) || !(eagerTag instanceof NBTTagList)) {
            return false;
        }
        NBTTagList raw = (NBTTagList) rawTag;
        NBTTagList eager = (NBTTagList) eagerTag;
        if (raw.size() != eager.size()) {
            return false;
        }
        boolean[] used = new boolean[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            NBTBase item = raw.k(i);
            boolean found = false;
            for (int j = 0; j < eager.size(); j++) {
                if (!used[j] && item.equals(eager.k(j))) {
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
