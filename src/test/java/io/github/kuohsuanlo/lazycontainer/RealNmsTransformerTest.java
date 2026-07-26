package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RealNmsTransformerTest {

    private Path nmsRoot;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(Boolean.getBoolean("lazycontainer.templatesReady"),
                "Run bash build.sh to compile the passive NMS templates first");
        nmsRoot = Path.of(System.getProperty("lazycontainer.nmsRoot", "nms-lib"));
        LazyContainerRuntime.injected = false;
        LazyContainerRuntime.target = "none";
    }

    @Test
    void transformsEveryPinnedNmsLayout() throws Exception {
        for (NmsTarget target : NmsTarget.all()) {
            LazyContainerRuntime.injected = false;
            LazyContainerRuntime.target = "none";
            LazyContainerTransformer transformer = new LazyContainerTransformer();

            byte[] transformedBase = transformer.transform(
                    null, target.baseClass, null, null, readClass(target, target.baseClass));
            assertNotNull(transformedBase, target.displayName + " base should be spliced");
            assertSplicedBase(transformedBase, target);

            for (String leaf : target.leafClasses) {
                byte[] transformedLeaf = transformer.transform(
                        null, leaf, null, null, readClass(target, leaf));
                assertNotNull(transformedLeaf, target.displayName + " should transform " + leaf);
                assertCompleteLeaf(transformedLeaf, target);
            }
        }
    }

    @Test
    void rejectsLeafWhenSaveRedirectIsMissing() throws Exception {
        NmsTarget target = NmsTarget.REGISTRY_NBT;
        LazyContainerTransformer transformer = new LazyContainerTransformer();
        assertNotNull(transformer.transform(
                null, target.baseClass, null, null, readClass(target, target.baseClass)));

        String chest = target.leafClasses.get(0);
        ClassNode node = readNode(readClass(target, chest));
        boolean removed = false;
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) instruction;
                    NmsTarget.Redirect redirect = target.redirectFor(
                            invocation.owner, invocation.name, invocation.desc);
                    if (redirect != null && redirect.kind == NmsTarget.RedirectKind.SAVE) {
                        invocation.owner = "invalid/ContainerHelper";
                        removed = true;
                    }
                }
            }
        }
        assertTrue(removed, "fixture should contain a save helper call");
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);

        assertNull(transformer.transform(null, chest, null, null, writer.toByteArray()),
                "a partial load-only transformation must be rejected");
    }

    @Test
    void rejectsUnknownBaseLayoutWithoutTouchingLeaves() throws Exception {
        NmsTarget target = NmsTarget.REGISTRY_NBT;
        ClassNode base = readNode(readClass(target, target.baseClass));
        boolean markerChanged = false;
        for (MethodNode method : base.methods) {
            if (method.name.equals("loadAdditional")
                    && method.desc.equals("(Lnet/minecraft/nbt/CompoundTag;"
                            + "Lnet/minecraft/core/HolderLookup$Provider;)V")) {
                method.name = "unsupported$loadAdditional";
                markerChanged = true;
            }
        }
        assertTrue(markerChanged, "fixture should contain the structural marker");
        ClassWriter writer = new ClassWriter(0);
        base.accept(writer);

        LazyContainerTransformer transformer = new LazyContainerTransformer();
        assertNull(transformer.transform(
                null, target.baseClass, null, null, writer.toByteArray()));
        assertFalse(LazyContainerRuntime.injected);
        assertEquals("none", LazyContainerRuntime.target);
        assertNull(transformer.transform(null, target.leafClasses.get(0), null, null,
                readClass(target, target.leafClasses.get(0))));
    }

    private byte[] readClass(NmsTarget target, String internalName) throws IOException {
        Path serverJar = nmsRoot.resolve(target.id).resolve("server.jar");
        assertTrue(serverJar.toFile().isFile(), "missing pinned NMS jar: " + serverJar);
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            JarEntry entry = jar.getJarEntry(internalName + ".class");
            assertNotNull(entry, internalName + " is missing from " + serverJar);
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static void assertSplicedBase(byte[] classBytes, NmsTarget target) {
        ClassNode node = readNode(classBytes);
        boolean pending = false;
        boolean ensure = false;
        boolean clear = false;
        for (FieldNode field : node.fields) {
            pending |= field.name.equals("lazycontainer$pending") && field.desc.equals("Z");
        }
        for (MethodNode method : node.methods) {
            ensure |= method.name.equals("lazycontainer$ensure") && method.desc.equals("()V");
            clear |= method.name.equals("lazycontainer$clear") && method.desc.equals("()V");
        }
        assertTrue(pending, target.id + " misses pending field");
        assertTrue(ensure, target.id + " misses ensure method");
        assertTrue(clear, target.id + " misses clear method");
    }

    private static void assertCompleteLeaf(byte[] classBytes, NmsTarget target) {
        ClassNode node = readNode(classBytes);
        int loadBridges = 0;
        int saveBridges = 0;
        int ensureCalls = 0;
        int clearCalls = 0;
        boolean originalHelperCall = false;
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode invocation = (MethodInsnNode) instruction;
                if (invocation.owner.equals(target.baseClass)
                        && invocation.name.equals("lazycontainer$load")) {
                    loadBridges++;
                } else if (invocation.owner.equals(target.baseClass)
                        && invocation.name.equals("lazycontainer$save")) {
                    saveBridges++;
                } else if (invocation.name.equals("lazycontainer$ensure")) {
                    ensureCalls++;
                } else if (invocation.name.equals("lazycontainer$clear")) {
                    clearCalls++;
                }
                originalHelperCall |= target.redirectFor(
                        invocation.owner, invocation.name, invocation.desc) != null;
            }
        }
        assertEquals(1, loadBridges, target.id + " should have one load bridge");
        assertEquals(1, saveBridges, target.id + " should have one save bridge");
        assertEquals(2, ensureCalls, target.id + " should guard getItems and getContents");
        assertEquals(1, clearCalls, target.id + " should guard setItems");
        assertFalse(originalHelperCall, target.id + " leaves an original helper call behind");
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
