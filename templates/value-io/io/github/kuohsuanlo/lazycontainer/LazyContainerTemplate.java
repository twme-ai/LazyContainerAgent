package io.github.kuohsuanlo.lazycontainer;

import java.lang.reflect.Field;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Compile-time template for the ValueInput/ValueOutput NMS family. Never loaded as a class. */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    public boolean lazycontainer$pending;
    public Tag lazycontainer$raw;
    public NonNullList<ItemStack> lazycontainer$items;
    public static volatile Field lazycontainer$inputField;
    public static volatile boolean lazycontainer$inputFieldFailed;

    protected LazyContainerTemplate(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static void lazycontainer$load(ValueInput input, NonNullList<ItemStack> items,
                                          LazyContainerTemplate self) {
        CompoundTag root = self.lazycontainer$inputRoot(input);
        if (root != null) {
            items.clear();
            self.lazycontainer$raw = root.get("Items");
            self.lazycontainer$items = items;
            self.lazycontainer$pending = true;
            LazyContainerRuntime.onStash();
            return;
        }
        self.lazycontainer$clear();
        ContainerHelper.loadAllItems(input, items);
        LazyContainerRuntime.onEagerLoad();
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
            ContainerHelper.loadAllItems(
                    TagValueInput.createGlobal(ProblemReporter.DISCARDING, input), items);
            this.lazycontainer$clear();
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            this.lazycontainer$pending = true;
            throw t;
        }
    }

    public static void lazycontainer$save(ValueOutput output, NonNullList<ItemStack> items,
                                          LazyContainerTemplate self) {
        if (!self.lazycontainer$trySaveRaw(output, true)) {
            ContainerHelper.saveAllItems(output, items);
        }
    }

    public static void lazycontainer$save(ValueOutput output, NonNullList<ItemStack> items,
                                          boolean allowEmpty, LazyContainerTemplate self) {
        if (!self.lazycontainer$trySaveRaw(output, allowEmpty)) {
            ContainerHelper.saveAllItems(output, items, allowEmpty);
        }
    }

    private boolean lazycontainer$trySaveRaw(ValueOutput output, boolean allowEmpty) {
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
        if (canWriteRaw && output instanceof TagValueOutput) {
            CompoundTag result = ((TagValueOutput) output).buildResult();
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
                            result.remove("Items");
                        } else {
                            result.put("Items", eager);
                        }
                        return true;
                    }
                }
            }
            result.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        this.lazycontainer$ensure();
        return false;
    }

    private CompoundTag lazycontainer$inputRoot(ValueInput input) {
        if (!(input instanceof TagValueInput) || lazycontainer$inputFieldFailed) {
            return null;
        }
        try {
            Field field = lazycontainer$inputField;
            if (field == null) {
                field = TagValueInput.class.getDeclaredField("input");
                field.setAccessible(true);
                lazycontainer$inputField = field;
            }
            return (CompoundTag) field.get(input);
        } catch (Throwable t) {
            lazycontainer$inputFieldFailed = true;
            System.err.println("[LazyContainer] cannot access TagValueInput.input; using eager load: " + t);
            return null;
        }
    }

    private Tag lazycontainer$eagerItems(Tag raw, int size, boolean allowEmpty) {
        CompoundTag input = new CompoundTag();
        input.put("Items", raw);
        NonNullList<ItemStack> parsed = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(
                TagValueInput.createGlobal(ProblemReporter.DISCARDING, input), parsed);
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, MinecraftServer.getServer().registryAccess());
        ContainerHelper.saveAllItems(output, parsed, allowEmpty);
        return output.buildResult().get("Items");
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
