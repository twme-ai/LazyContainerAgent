package io.github.kuohsuanlo.lazycontainer;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Lazy container bytecode injection with structural NMS API detection.
 *
 * <p>The transformer supports the ValueInput/ValueOutput API, the 1.20.6 registry-aware NBT API,
 * and both mappings shipped for Paper 1.19.4. Each family has a javac-verified template. A leaf is
 * modified only when its load, save, getItems, setItems, and Paper getContents interception points
 * all match. Partial matches are returned unchanged.</p>
 */
public final class LazyContainerTransformer implements ClassFileTransformer {

    private static final String TEMPLATE = "io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate";
    private static final String PREFIX = "lazycontainer$";

    private volatile NmsTarget target;
    private volatile List<FieldNode> spliceFields;
    private volatile List<MethodNode> spliceMethods;
    private volatile boolean unsupportedBaseReported;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        try {
            NmsTarget activeTarget = target;
            if (activeTarget == null && NmsTarget.isKnownBaseClass(className)) {
                NmsTarget detected = NmsTarget.detect(className, classfileBuffer);
                if (detected == null) {
                    reportUnsupportedBase(className);
                    return null;
                }
                return spliceBase(classfileBuffer, detected);
            }
            if (activeTarget != null && activeTarget.isLeaf(className)) {
                if (!LazyContainerRuntime.injected) {
                    System.err.println("[LazyContainer] base not spliced; skip leaf " + className);
                    return null;
                }
                return transformLeaf(classfileBuffer, className, activeTarget);
            }
        } catch (Throwable t) {
            System.err.println("[LazyContainer] transform failed for " + className
                    + " - leaving vanilla: " + t);
            t.printStackTrace();
        }
        return null;
    }

    static boolean isCandidateClass(String binaryName) {
        return NmsTarget.isCandidateClass(binaryName.replace('.', '/'));
    }

    static boolean isBaseClass(String binaryName) {
        return NmsTarget.isBaseClass(binaryName);
    }

    private synchronized byte[] spliceBase(byte[] buffer, NmsTarget detected) {
        if (target != null) {
            return null;
        }
        ClassNode baseNode = new ClassNode();
        new ClassReader(buffer).accept(baseNode,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (FieldNode field : baseNode.fields) {
            if (field.name.startsWith(PREFIX)) {
                System.err.println("[LazyContainer] base already contains " + PREFIX
                        + " members; leaving it unchanged");
                return null;
            }
        }

        loadSpliceMembers(detected);
        if (!validTemplate(detected)) {
            System.err.println("[LazyContainer] FATAL: template members unavailable for "
                    + detected.displayName + "; base not spliced");
            return null;
        }

        ClassReader reader = new ClassReader(buffer);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (FieldNode field : spliceFields) {
                    field.accept(this);
                }
                for (MethodNode method : spliceMethods) {
                    method.accept(this);
                }
                super.visitEnd();
            }
        };
        reader.accept(visitor, 0);

        target = detected;
        LazyContainerRuntime.target = detected.displayName;
        LazyContainerRuntime.injected = true;
        System.out.println("[LazyContainer] detected " + detected.displayName
                + " (layout=" + detected.id + ")");
        System.out.println("[LazyContainer] spliced " + spliceFields.size() + " fields + "
                + spliceMethods.size() + " methods into " + detected.baseClass);
        return writer.toByteArray();
    }

    private void loadSpliceMembers(NmsTarget detected) {
        try (InputStream in = LazyContainerTransformer.class.getResourceAsStream(detected.templateResource)) {
            if (in == null) {
                System.err.println("[LazyContainer] FATAL: template resource not found: "
                        + detected.templateResource);
                return;
            }
            ClassReader templateReader = new ClassReader(in.readAllBytes());
            ClassNode remapped = new ClassNode();
            templateReader.accept(new ClassRemapper(
                    remapped, new SimpleRemapper(TEMPLATE, detected.baseClass)), 0);

            List<FieldNode> fields = new ArrayList<>();
            for (FieldNode field : remapped.fields) {
                if (field.name.startsWith(PREFIX)) {
                    fields.add(field);
                }
            }
            List<MethodNode> methods = new ArrayList<>();
            for (MethodNode method : remapped.methods) {
                if (method.name.startsWith(PREFIX)) {
                    methods.add(method);
                }
            }
            spliceFields = fields;
            spliceMethods = methods;
        } catch (Throwable t) {
            System.err.println("[LazyContainer] FATAL: reading template failed: " + t);
            t.printStackTrace();
        }
    }

    private boolean validTemplate(NmsTarget detected) {
        if (spliceFields == null || spliceMethods == null || spliceMethods.isEmpty()) {
            return false;
        }
        boolean pendingField = false;
        Set<String> methods = new HashSet<>();
        for (FieldNode field : spliceFields) {
            if ("lazycontainer$pending".equals(field.name) && "Z".equals(field.desc)) {
                pendingField = true;
            }
        }
        for (MethodNode method : spliceMethods) {
            methods.add(method.name + method.desc);
        }
        if (!pendingField || !methods.contains("lazycontainer$ensure()V")
                || !methods.contains("lazycontainer$clear()V")) {
            return false;
        }
        for (NmsTarget.Redirect redirect : detected.redirects) {
            String bridgeDescriptor = detected.bridgeDescriptor(redirect.descriptor);
            if (!methods.contains(redirect.replacementMethod + bridgeDescriptor)) {
                System.err.println("[LazyContainer] template misses " + redirect.replacementMethod
                        + bridgeDescriptor);
                return false;
            }
        }
        return true;
    }

    byte[] transformLeaf(byte[] buffer, String className, NmsTarget activeTarget) {
        TransformStats stats = new TransformStats();
        ClassReader reader = new ClassReader(buffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
                MethodVisitor redirect = new RedirectMethodVisitor(
                        downstream, access, activeTarget, stats);
                int guard = guardKind(name, descriptor, activeTarget);
                if (guard != GUARD_NONE) {
                    stats.recordGuard(guard);
                    return new GuardMethodVisitor(redirect, className, guard);
                }
                return redirect;
            }
        };
        reader.accept(visitor, 0);

        if (!stats.complete()) {
            System.err.println("[LazyContainer] incompatible leaf " + className + " for "
                    + activeTarget.displayName + ": " + stats + " - leaving vanilla");
            return null;
        }
        System.out.println("[LazyContainer] transformed leaf " + className + " (" + stats + ")");
        return writer.toByteArray();
    }

    private void reportUnsupportedBase(String className) {
        if (!unsupportedBaseReported) {
            unsupportedBaseReported = true;
            System.err.println("[LazyContainer] unsupported NMS layout in " + className
                    + "; agent stays inactive and all classes remain vanilla");
        }
    }

    private static final int GUARD_NONE = 0;
    private static final int GUARD_ENSURE_ITEMS = 1;
    private static final int GUARD_ENSURE_CONTENTS = 2;
    private static final int GUARD_CLEAR = 3;

    private static int guardKind(String name, String descriptor, NmsTarget activeTarget) {
        if (name.equals(activeTarget.getItemsMethod)
                && descriptor.equals("()L" + activeTarget.nonNullListClass + ";")) {
            return GUARD_ENSURE_ITEMS;
        }
        if (name.equals("getContents") && descriptor.equals("()Ljava/util/List;")) {
            return GUARD_ENSURE_CONTENTS;
        }
        if (name.equals(activeTarget.setItemsMethod)
                && descriptor.equals("(L" + activeTarget.nonNullListClass + ";)V")) {
            return GUARD_CLEAR;
        }
        return GUARD_NONE;
    }

    private static final class GuardMethodVisitor extends MethodVisitor {
        private final String owner;
        private final int kind;

        GuardMethodVisitor(MethodVisitor visitor, String owner, int kind) {
            super(Opcodes.ASM9, visitor);
            this.owner = owner;
            this.kind = kind;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (kind == GUARD_ENSURE_ITEMS || kind == GUARD_ENSURE_CONTENTS) {
                Label skip = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, owner, "lazycontainer$pending", "Z");
                super.visitJumpInsn(Opcodes.IFEQ, skip);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "lazycontainer$ensure", "()V", false);
                super.visitLabel(skip);
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            } else {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "lazycontainer$clear", "()V", false);
            }
        }

    }

    /** Replaces a static helper call with a static template bridge taking {@code this} last. */
    private static final class RedirectMethodVisitor extends MethodVisitor {
        private final NmsTarget target;
        private final TransformStats stats;
        private final boolean instanceMethod;

        RedirectMethodVisitor(MethodVisitor visitor, int access,
                              NmsTarget target, TransformStats stats) {
            super(Opcodes.ASM9, visitor);
            this.target = target;
            this.stats = stats;
            this.instanceMethod = (access & Opcodes.ACC_STATIC) == 0;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            NmsTarget.Redirect redirect = target.redirectFor(owner, name, descriptor);
            if (instanceMethod && opcode == Opcodes.INVOKESTATIC && redirect != null) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, target.baseClass,
                        redirect.replacementMethod, target.bridgeDescriptor(descriptor), false);
                stats.recordRedirect(redirect.kind);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private static final class TransformStats {
        int loads;
        int saves;
        int getItems;
        int getContents;
        int setItems;

        void recordRedirect(NmsTarget.RedirectKind kind) {
            if (kind == NmsTarget.RedirectKind.LOAD) {
                loads++;
            } else {
                saves++;
            }
        }

        void recordGuard(int guard) {
            if (guard == GUARD_ENSURE_ITEMS) {
                getItems++;
            } else if (guard == GUARD_ENSURE_CONTENTS) {
                getContents++;
            } else if (guard == GUARD_CLEAR) {
                setItems++;
            }
        }

        boolean complete() {
            return loads == 1 && saves == 1 && getItems == 1 && getContents == 1 && setItems == 1;
        }

        @Override
        public String toString() {
            return "load=" + loads + " save=" + saves + " getItems=" + getItems
                    + " getContents=" + getContents + " setItems=" + setItems;
        }
    }
}
