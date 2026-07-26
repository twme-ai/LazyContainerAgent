package io.github.kuohsuanlo.lazycontainer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structurally-detected NMS layouts supported by the agent.
 *
 * <p>The Minecraft version string is deliberately not used as the compatibility gate. Paper can
 * change implementation details between builds while keeping the same marketing version. The
 * transformer instead requires the exact base-class marker and helper descriptors it will patch.</p>
 */
final class NmsTarget {

    enum RedirectKind {
        LOAD,
        SAVE
    }

    static final class Redirect {
        final RedirectKind kind;
        final String helperMethod;
        final String descriptor;
        final String replacementMethod;

        Redirect(RedirectKind kind, String helperMethod, String descriptor, String replacementMethod) {
            this.kind = kind;
            this.helperMethod = helperMethod;
            this.descriptor = descriptor;
            this.replacementMethod = replacementMethod;
        }
    }

    private static final String MOJANG_BLOCK_ENTITY = "net/minecraft/world/level/block/entity/";
    private static final String MOJANG_BASE = MOJANG_BLOCK_ENTITY + "BaseContainerBlockEntity";
    private static final String MOJANG_HELPER = "net/minecraft/world/ContainerHelper";
    private static final String NON_NULL_LIST = "net/minecraft/core/NonNullList";
    private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String VALUE_INPUT = "net/minecraft/world/level/storage/ValueInput";
    private static final String VALUE_OUTPUT = "net/minecraft/world/level/storage/ValueOutput";

    static final NmsTarget VALUE_IO = new NmsTarget(
            "value-io",
            "Paper 26.2 / 26.1.x / 1.21.11 / 1.21.8",
            MOJANG_BASE,
            "loadAdditional",
            "(L" + VALUE_INPUT + ";)V",
            MOJANG_HELPER,
            NON_NULL_LIST,
            "getItems",
            "setItems",
            "/templates/value-io/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class",
            mojangLeaves(),
            Arrays.asList(
                    new Redirect(RedirectKind.LOAD, "loadAllItems",
                            "(L" + VALUE_INPUT + ";L" + NON_NULL_LIST + ";)V", "lazycontainer$load"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + VALUE_OUTPUT + ";L" + NON_NULL_LIST + ";)V", "lazycontainer$save"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + VALUE_OUTPUT + ";L" + NON_NULL_LIST + ";Z)V", "lazycontainer$save")));

    static final NmsTarget REGISTRY_NBT = new NmsTarget(
            "registry-nbt",
            "Paper 1.20.6",
            MOJANG_BASE,
            "loadAdditional",
            "(L" + COMPOUND_TAG + ";L" + PROVIDER + ";)V",
            MOJANG_HELPER,
            NON_NULL_LIST,
            "getItems",
            "setItems",
            "/templates/registry-nbt/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class",
            mojangLeaves(),
            Arrays.asList(
                    new Redirect(RedirectKind.LOAD, "loadAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";L" + PROVIDER + ";)V",
                            "lazycontainer$load"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";L" + PROVIDER + ";)L"
                                    + COMPOUND_TAG + ";",
                            "lazycontainer$save"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";ZL" + PROVIDER + ";)L"
                                    + COMPOUND_TAG + ";",
                            "lazycontainer$save")));

    static final NmsTarget LEGACY_MOJANG = new NmsTarget(
            "legacy-mojang",
            "Paper 1.19.4 (Mojang-mapped artifact)",
            MOJANG_BASE,
            "load",
            "(L" + COMPOUND_TAG + ";)V",
            MOJANG_HELPER,
            NON_NULL_LIST,
            "getItems",
            "setItems",
            "/templates/legacy-mojang/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class",
            mojangLeaves(),
            Arrays.asList(
                    new Redirect(RedirectKind.LOAD, "loadAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";)V", "lazycontainer$load"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";)L" + COMPOUND_TAG + ";",
                            "lazycontainer$save"),
                    new Redirect(RedirectKind.SAVE, "saveAllItems",
                            "(L" + COMPOUND_TAG + ";L" + NON_NULL_LIST + ";Z)L" + COMPOUND_TAG + ";",
                            "lazycontainer$save")));

    static final NmsTarget LEGACY_SPIGOT = new NmsTarget(
            "legacy-spigot",
            "Paper 1.19.4 (default Spigot-mapped artifact)",
            MOJANG_BLOCK_ENTITY + "TileEntityContainer",
            "a",
            "(Lnet/minecraft/nbt/NBTTagCompound;)V",
            "net/minecraft/world/ContainerUtil",
            NON_NULL_LIST,
            "f",
            "a",
            "/templates/legacy-spigot/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class",
            Arrays.asList(
                    MOJANG_BLOCK_ENTITY + "TileEntityChest",
                    MOJANG_BLOCK_ENTITY + "TileEntityBarrel",
                    MOJANG_BLOCK_ENTITY + "TileEntityShulkerBox"),
            Arrays.asList(
                    new Redirect(RedirectKind.LOAD, "b",
                            "(Lnet/minecraft/nbt/NBTTagCompound;L" + NON_NULL_LIST + ";)V",
                            "lazycontainer$load"),
                    new Redirect(RedirectKind.SAVE, "a",
                            "(Lnet/minecraft/nbt/NBTTagCompound;L" + NON_NULL_LIST
                                    + ";)Lnet/minecraft/nbt/NBTTagCompound;",
                            "lazycontainer$save"),
                    new Redirect(RedirectKind.SAVE, "a",
                            "(Lnet/minecraft/nbt/NBTTagCompound;L" + NON_NULL_LIST
                                    + ";Z)Lnet/minecraft/nbt/NBTTagCompound;",
                            "lazycontainer$save")));

    private static final List<NmsTarget> ALL = Collections.unmodifiableList(Arrays.asList(
            VALUE_IO, REGISTRY_NBT, LEGACY_MOJANG, LEGACY_SPIGOT));

    final String id;
    final String displayName;
    final String baseClass;
    final String helperClass;
    final String nonNullListClass;
    final String getItemsMethod;
    final String setItemsMethod;
    final String templateResource;
    final List<String> leafClasses;
    final List<Redirect> redirects;

    private final String markerMethod;
    private final String markerDescriptor;
    private final Set<String> leafClassSet;

    private NmsTarget(String id, String displayName, String baseClass,
                      String markerMethod, String markerDescriptor,
                      String helperClass, String nonNullListClass,
                      String getItemsMethod, String setItemsMethod, String templateResource,
                      List<String> leafClasses, List<Redirect> redirects) {
        this.id = id;
        this.displayName = displayName;
        this.baseClass = baseClass;
        this.markerMethod = markerMethod;
        this.markerDescriptor = markerDescriptor;
        this.helperClass = helperClass;
        this.nonNullListClass = nonNullListClass;
        this.getItemsMethod = getItemsMethod;
        this.setItemsMethod = setItemsMethod;
        this.templateResource = templateResource;
        this.leafClasses = Collections.unmodifiableList(leafClasses);
        this.leafClassSet = Collections.unmodifiableSet(new HashSet<>(leafClasses));
        this.redirects = Collections.unmodifiableList(redirects);
    }

    static List<NmsTarget> all() {
        return ALL;
    }

    static boolean isKnownBaseClass(String className) {
        for (NmsTarget target : ALL) {
            if (target.baseClass.equals(className)) {
                return true;
            }
        }
        return false;
    }

    static boolean isCandidateClass(String className) {
        for (NmsTarget target : ALL) {
            if (target.baseClass.equals(className) || target.leafClassSet.contains(className)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBaseClass(String binaryName) {
        String internalName = binaryName.replace('.', '/');
        return isKnownBaseClass(internalName);
    }

    static boolean isLeafClass(String binaryName) {
        String internalName = binaryName.replace('.', '/');
        for (NmsTarget target : ALL) {
            if (target.leafClassSet.contains(internalName)) {
                return true;
            }
        }
        return false;
    }

    static NmsTarget detect(String className, byte[] classBytes) {
        if (!isKnownBaseClass(className)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (NmsTarget target : ALL) {
            if (target.baseClass.equals(className) && target.hasMarker(node)) {
                return target;
            }
        }
        return null;
    }

    boolean isLeaf(String className) {
        return leafClassSet.contains(className);
    }

    Redirect redirectFor(String owner, String name, String descriptor) {
        if (!helperClass.equals(owner)) {
            return null;
        }
        for (Redirect redirect : redirects) {
            if (redirect.helperMethod.equals(name) && redirect.descriptor.equals(descriptor)) {
                return redirect;
            }
        }
        return null;
    }

    String bridgeDescriptor(String descriptor) {
        int endOfArguments = descriptor.indexOf(')');
        if (endOfArguments < 0) {
            throw new IllegalArgumentException("invalid method descriptor: " + descriptor);
        }
        return descriptor.substring(0, endOfArguments)
                + "L" + baseClass + ";" + descriptor.substring(endOfArguments);
    }

    private boolean hasMarker(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (markerMethod.equals(method.name) && markerDescriptor.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> mojangLeaves() {
        return Arrays.asList(
                MOJANG_BLOCK_ENTITY + "ChestBlockEntity",
                MOJANG_BLOCK_ENTITY + "BarrelBlockEntity",
                MOJANG_BLOCK_ENTITY + "ShulkerBoxBlockEntity");
    }
}
