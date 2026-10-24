package dev.lich.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Lich's compat pass, run on every class Lich defines, before any LaunchWrapper transformer. It
 * <ul>
 *   <li>renames references to removed JDK types to Lich's look-alike shims
 *       ({@code java/util/jar/Pack200} → {@code dev/lich/compat/jdk/Pack200}),</li>
 *   <li>renames whole packages, such as relocated copies of old libraries that can't run on Java 25
 *       ({@code org/spongepowered/asm/lib/} → {@code org/objectweb/asm/}),</li>
 *   <li>renames string constants that name removed classes for reflection
 *       ({@code "sun.reflect.FieldAccessor"}),</li>
 *   <li>redirects calls to JDK methods that now throw or behave differently to static replacements,</li>
 *   <li>moves subclasses of library classes whose constructors changed onto Lich subclasses that keep
 *       the old constructors working ("rebases"),</li>
 *   <li>replaces instantiations of a library class with instantiations of a Lich subclass,</li>
 *   <li>replaces int constants in classes that use a given library (ASM API levels),</li>
 *   <li>marks method handles to interface methods as such, which tools built on ASM 5.0 left out,</li>
 *   <li>rewrites calls and constructor calls by {@link CallTable}: hundreds of members of one library
 *       (LWJGL 2's, onto LWJGL 3),</li>
 *   <li>passes the first argument of a call (a superclass constructor call included) through a static
 *       function, for arguments Java 8 accepted and newer JDKs reject,</li>
 *   <li>and applies {@link ClassPatch}es to classes by name.</li>
 * </ul>
 * String renames and redirects can be limited to classes that also contain a marker string, so a
 * common call like {@code Class.getDeclaredField} is only redirected in classes that use the hack it
 * fixes.
 *
 * <p>Most classes need none of it, so each rule first checks the raw class bytes for the strings it
 * needs; only classes that some rule might apply to are parsed.
 */
public final class CompatTransformer {
    /** Told about every change, so boot can log which class used which removed API. */
    @FunctionalInterface
    public interface Listener {
        void changed(String className, String what);
    }

    private record Member(String owner, String name, String descriptor) {
    }

    /**
     * Where a call goes: always a static method. A {@code null} descriptor means the call's own, with the
     * receiver of an instance call as the first parameter.
     */
    private record Target(String owner, String name, String descriptor) {
    }

    /**
     * Many call rewrites for one library, looked at only in classes that contain {@code gate} (a package
     * prefix such as {@code org/lwjgl/}): one byte search per class instead of one per rule.
     */
    public static final class CallTable {
        private final byte[] gate;
        private final Map<Member, Target> calls = new HashMap<>();
        private final Map<Member, Target> constructors = new HashMap<>();

        public CallTable(String gate) {
            this.gate = utf8(gate);
        }

        /**
         * Calls to {@code owner.name descriptor} call the static {@code target.name} instead, which takes the
         * receiver of an instance method as its first parameter.
         */
        public CallTable redirect(String owner, String name, String descriptor, String target) {
            calls.put(new Member(owner, name, descriptor), new Target(target, name, null));
            return this;
        }

        /**
         * Calls to the static {@code owner.name descriptor} call the static {@code target.targetName
         * targetDescriptor} instead, which takes the same arguments (a parameter may be a supertype).
         */
        public CallTable move(String owner, String name, String descriptor, String target, String targetName,
                              String targetDescriptor) {
            calls.put(new Member(owner, name, descriptor), new Target(target, targetName, targetDescriptor));
            return this;
        }

        /**
         * {@code new owner(...)} with constructor {@code descriptor} calls the static factory {@code
         * target.factory}, which takes the constructor's arguments and returns an {@code owner}.
         */
        public CallTable redirectConstructor(String owner, String descriptor, String target, String factory) {
            String factoryDescriptor = descriptor.substring(0, descriptor.indexOf(')') + 1) + "L" + owner + ";";
            constructors.put(new Member(owner, "<init>", descriptor), new Target(target, factory, factoryDescriptor));
            return this;
        }

        public int size() {
            return calls.size() + constructors.size();
        }
    }

    /** A rule's byte check: all of {@code required}, and one of {@code anyOf} unless that's empty. */
    private record Needles(byte[][] required, byte[][] anyOf) {
        static Needles of(List<String> required, List<String> anyOf) {
            return new Needles(required.stream().map(CompatTransformer::utf8).toArray(byte[][]::new),
                    anyOf.stream().map(CompatTransformer::utf8).toArray(byte[][]::new));
        }

        boolean foundIn(byte[] bytes) {
            for (byte[] needle : required) {
                if (indexOf(bytes, needle) < 0) {
                    return false;
                }
            }
            if (anyOf.length == 0) {
                return true;
            }
            for (byte[] needle : anyOf) {
                if (indexOf(bytes, needle) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private record TypeRename(String from, String to, Needles needles) {
    }

    private record StringRename(String from, String to, Needles needles) {
    }

    /** {@code when} is asked the first time a class needs the rename, and its answer kept. */
    private record PackageRename(String from, String to, byte[] needle, BooleanSupplier when) {
    }

    /** {@link PackageRename#when}'s answers, by {@code from}. */
    private final Map<String, Boolean> packageRenameDecisions = new ConcurrentHashMap<>();

    /** Applies when one of {@code needles} is found: the owner may still be spelled with a renamed package. */
    private record Redirect(Member member, Target target, List<Needles> needles) {
        boolean foundIn(byte[] bytes) {
            for (Needles candidate : needles) {
                if (candidate.foundIn(bytes)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record RedirectSpec(Member member, String target, List<String> markers) {
    }

    private record ArgumentFilterSpec(Member member, String filterOwner, String filterName) {
    }

    /** {@code ldc from} becomes {@code ldc to} in classes containing one of {@code markers}. */
    private record IntConstant(int from, int to, byte[] constant, List<byte[]> markers, String why) {
        boolean foundIn(byte[] bytes) {
            if (indexOf(bytes, constant) < 0) {
                return false;
            }
            for (byte[] marker : markers) {
                if (indexOf(bytes, marker) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private record IntConstantSpec(int from, int to, String marker, String why) {
    }

    /** Classes extending {@code from} extend {@code to} instead; {@code needles} are its spellings. */
    private record Rebase(String from, String to, List<byte[]> needles) {
        boolean foundIn(byte[] bytes) {
            for (byte[] needle : needles) {
                if (indexOf(bytes, needle) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<TypeRename> typeRenames;
    private final List<PackageRename> packageRenames;
    private final List<StringRename> stringRenames;
    private final List<Redirect> redirects;
    private final List<Rebase> rebases;
    private final List<Rebase> instantiations;
    private final List<IntConstant> intConstants;
    private final List<CallTable> callTables;
    private final List<Redirect> argumentFilters;
    private final Predicate<String> interfaces;
    private final Map<String, List<ClassPatch>> patches;
    private final Listener listener;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private CompatTransformer(Builder builder) {
        this.typeRenames = List.copyOf(builder.typeRenames);
        this.packageRenames = List.copyOf(builder.packageRenames);
        this.stringRenames = List.copyOf(builder.stringRenames);
        List<Redirect> redirects = new ArrayList<>();
        for (RedirectSpec spec : builder.redirects) {
            Member member = spec.member();
            List<Needles> needles = new ArrayList<>();
            for (String owner : spellings(member.owner())) {
                needles.add(Needles.of(List.of(owner, member.name()), spec.markers()));
            }
            redirects.add(new Redirect(member, new Target(spec.target(), member.name(), null), List.copyOf(needles)));
        }
        this.redirects = List.copyOf(redirects);
        List<Redirect> argumentFilters = new ArrayList<>();
        for (ArgumentFilterSpec spec : builder.argumentFilters) {
            Member member = spec.member();
            List<Needles> needles = new ArrayList<>();
            for (String owner : spellings(member.owner())) {
                needles.add(Needles.of(List.of(owner, member.name()), List.of()));
            }
            String argument = Type.getArgumentTypes(member.descriptor())[0].getDescriptor();
            argumentFilters.add(new Redirect(member, new Target(spec.filterOwner(), spec.filterName(),
                    "(" + argument + ")" + argument), List.copyOf(needles)));
        }
        this.argumentFilters = List.copyOf(argumentFilters);
        List<Rebase> rebases = new ArrayList<>();
        builder.rebases.forEach((from, to) ->
                rebases.add(new Rebase(from, to, spellings(from).stream().map(CompatTransformer::utf8).toList())));
        this.rebases = List.copyOf(rebases);
        List<Rebase> instantiations = new ArrayList<>();
        builder.instantiations.forEach((from, to) ->
                instantiations.add(new Rebase(from, to, spellings(from).stream().map(CompatTransformer::utf8).toList())));
        this.instantiations = List.copyOf(instantiations);
        List<IntConstant> intConstants = new ArrayList<>();
        for (IntConstantSpec spec : builder.intConstants) {
            // CONSTANT_Integer: tag 3, then the value big-endian. Small values are bipush/sipush, not ldc.
            byte[] constant = {3, (byte) (spec.from() >>> 24), (byte) (spec.from() >>> 16), (byte) (spec.from() >>> 8), (byte) spec.from()};
            intConstants.add(new IntConstant(spec.from(), spec.to(), constant,
                    spellings(spec.marker()).stream().map(CompatTransformer::utf8).toList(), spec.why()));
        }
        this.intConstants = List.copyOf(intConstants);
        this.callTables = List.copyOf(builder.callTables);
        this.interfaces = builder.interfaces;
        Map<String, List<ClassPatch>> patches = new HashMap<>();
        builder.patches.forEach((name, list) -> patches.put(name, List.copyOf(list)));
        this.patches = Map.copyOf(patches);
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@code type} and its spellings before a package rename: rules match on raw bytes, where a class
     * still names the type by its old package.
     */
    private List<String> spellings(String type) {
        List<String> spellings = new ArrayList<>();
        spellings.add(type);
        for (PackageRename rename : packageRenames) {
            if (type.startsWith(rename.to())) {
                spellings.add(rename.from() + type.substring(rename.to().length()));
            }
        }
        return spellings;
    }

    /**
     * Returns the transformed class, or {@code bytes} itself (the same array) when nothing applies.
     *
     * @param name the class's binary name ({@code a.b.C}), used to look up patches
     */
    public byte[] transform(String name, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Map<String, String> renames = new HashMap<>();
        for (TypeRename rename : typeRenames) {
            if (rename.needles().foundIn(bytes)) {
                renames.put(rename.from(), rename.to());
            }
        }
        String internalName = name.replace('.', '/');
        List<PackageRename> packages = new ArrayList<>();
        for (PackageRename rename : packageRenames) {
            // A class inside the old package keeps its names: nothing should load it once it's unreferenced.
            if (!internalName.startsWith(rename.from()) && indexOf(bytes, rename.needle()) >= 0
                    && packageRenameDecisions.computeIfAbsent(rename.from(), from -> rename.when().getAsBoolean())) {
                packages.add(rename);
            }
        }
        Map<String, String> strings = new HashMap<>();
        for (StringRename rename : stringRenames) {
            if (rename.needles().foundIn(bytes)) {
                strings.put(rename.from(), rename.to());
            }
        }
        Map<Member, Target> calls = new HashMap<>();
        for (Redirect redirect : redirects) {
            if (redirect.foundIn(bytes)) {
                calls.put(redirect.member(), redirect.target());
            }
        }
        Map<Member, Target> filters = new HashMap<>();
        for (Redirect filter : argumentFilters) {
            if (filter.foundIn(bytes)) {
                filters.put(filter.member(), filter.target());
            }
        }
        List<CallTable> tables = new ArrayList<>();
        for (CallTable table : callTables) {
            if (indexOf(bytes, table.gate) >= 0) {
                tables.add(table);
            }
        }
        List<Rebase> rebaseCandidates = new ArrayList<>();
        for (Rebase rebase : rebases) {
            if (rebase.foundIn(bytes)) {
                rebaseCandidates.add(rebase);
            }
        }
        List<Rebase> instantiationCandidates = new ArrayList<>();
        for (Rebase instantiation : instantiations) {
            if (instantiation.foundIn(bytes) && !internalName.equals(instantiation.to())) {
                instantiationCandidates.add(instantiation);
            }
        }
        List<IntConstant> constantCandidates = new ArrayList<>();
        for (IntConstant constant : intConstants) {
            if (constant.foundIn(bytes)) {
                constantCandidates.add(constant);
            }
        }
        boolean handles = interfaces != null && indexOf(bytes, LAMBDA_METAFACTORY) >= 0;
        List<ClassPatch> classPatches = patches.getOrDefault(name, List.of());
        if (renames.isEmpty() && packages.isEmpty() && strings.isEmpty() && calls.isEmpty() && tables.isEmpty()
                && filters.isEmpty()
                && rebaseCandidates.isEmpty() && instantiationCandidates.isEmpty() && constantCandidates.isEmpty()
                && !handles && classPatches.isEmpty()) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        boolean changed = false;
        if (!renames.isEmpty() || !packages.isEmpty()) {
            // Counted as a change even if the name only matched inside a string: rewriting is harmless.
            reader.accept(new ClassRemapper(node, new Renamer(renames, packages)), 0);
            changed = true;
            if (!renames.isEmpty()) {
                listener.changed(name, "renamed removed JDK types " + renames.keySet());
            }
            for (PackageRename rename : packages) {
                reportOncePerNamespace(name, "renamed package " + rename.from() + " to " + rename.to());
            }
        } else {
            reader.accept(node, 0);
        }
        changed |= renameStrings(name, node, strings);
        changed |= redirect(name, node, calls);
        changed |= filterArguments(name, node, filters);
        for (CallTable table : tables) {
            changed |= redirectConstructors(name, node, table.constructors);
            changed |= redirectCalls(name, node, table.calls);
        }
        changed |= rebase(name, node, rebaseCandidates);
        changed |= replaceInstantiations(name, node, instantiationCandidates);
        changed |= replaceIntConstants(name, node, constantCandidates);
        changed |= handles && fixInterfaceHandles(name, node);
        for (ClassPatch patch : classPatches) {
            if (patch.apply(node)) {
                changed = true;
                listener.changed(name, "patched by " + patch.getClass().getName());
            }
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private boolean renameStrings(String className, ClassNode node, Map<String, String> strings) {
        if (strings.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value && strings.containsKey(value)) {
                    ldc.cst = strings.get(value);
                    listener.changed(className, "renamed string \"" + value + "\" to \"" + ldc.cst + "\"");
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean redirect(String className, ClassNode node, Map<Member, Target> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESPECIAL) {
                    continue;
                }
                Target target = calls.get(new Member(call.owner, call.name, call.desc));
                if (target == null) {
                    continue;
                }
                listener.changed(className, "redirected " + call.owner + "." + call.name + " to " + target.owner());
                retarget(call, target);
                changed = true;
            }
        }
        return changed;
    }

    /** Like {@link #redirect}, but each rewritten member is reported once per namespace. */
    private boolean redirectCalls(String className, ClassNode node, Map<Member, Target> calls) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESPECIAL) {
                    continue;
                }
                Target target = calls.get(new Member(call.owner, call.name, call.desc));
                if (target == null) {
                    continue;
                }
                reportOncePerNamespace(className, "redirected " + call.owner + "." + call.name + call.desc
                        + " to " + target.owner() + "." + target.name());
                retarget(call, target);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Inserts a call to the filter before each matching call, on its first argument: right on top of the
     * stack for one argument, under the second (one slot) for two. Other shapes are left alone. The stack
     * never grows, and no branch is added.
     */
    private boolean filterArguments(String className, ClassNode node, Map<Member, Target> filters) {
        if (filters.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                Target filter = filters.get(new Member(call.owner, call.name, call.desc));
                Type[] arguments = Type.getArgumentTypes(call.desc);
                if (filter == null || arguments.length > 2 || (arguments.length == 2 && arguments[1].getSize() != 1)) {
                    continue;
                }
                InsnList before = new InsnList();
                if (arguments.length == 2) {
                    before.add(new InsnNode(Opcodes.SWAP));
                }
                before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, filter.owner(), filter.name(), filter.descriptor(), false));
                if (arguments.length == 2) {
                    before.add(new InsnNode(Opcodes.SWAP));
                }
                method.instructions.insertBefore(call, before);
                reportOncePerNamespace(className, "filtered the first argument of " + call.owner + "." + call.name
                        + call.desc + " through " + filter.owner() + "." + filter.name());
                changed = true;
            }
        }
        return changed;
    }

    /** Points {@code call} at the static {@code target}; the stack stays exactly the same. */
    private static void retarget(MethodInsnNode call, Target target) {
        if (target.descriptor() != null) {
            call.desc = target.descriptor();
        } else if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            // The receiver becomes the first argument.
            call.desc = "(L" + call.owner + ";" + call.desc.substring(1);
        }
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = target.owner();
        call.name = target.name();
        call.itf = false;
    }

    /**
     * Turns {@code new Owner; dup; <arguments>; invokespecial Owner.<init>} into {@code <arguments>;
     * invokestatic factory}, which leaves the same object on the stack. Left alone when a stack map frame
     * lies in between: it would still name the removed {@code new} as an uninitialized type.
     */
    private boolean redirectConstructors(String className, ClassNode node, Map<Member, Target> constructors) {
        if (constructors.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            Deque<TypeInsnNode> pendingNews = new ArrayDeque<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                    pendingNews.push(type);
                    continue;
                }
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.name.equals("<init>")) {
                    continue;
                }
                // Otherwise it's a constructor's own super(...) or this(...) call.
                if (pendingNews.isEmpty() || !pendingNews.peek().desc.equals(call.owner)) {
                    continue;
                }
                TypeInsnNode allocation = pendingNews.pop();
                Target factory = constructors.get(new Member(call.owner, call.name, call.desc));
                AbstractInsnNode dup = allocation.getNext();
                if (factory == null || dup == null || dup.getOpcode() != Opcodes.DUP || hasFrameBetween(dup, call)) {
                    continue;
                }
                method.instructions.remove(allocation);
                method.instructions.remove(dup);
                reportOncePerNamespace(className, "redirected new " + call.owner + call.desc
                        + " to " + factory.owner() + "." + factory.name());
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = factory.owner();
                call.name = factory.name();
                call.desc = factory.descriptor();
                call.itf = false;
                changed = true;
            }
        }
        return changed;
    }

    private static boolean hasFrameBetween(AbstractInsnNode from, AbstractInsnNode to) {
        for (AbstractInsnNode insn = from; insn != to; insn = insn.getNext()) {
            if (insn instanceof FrameNode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns {@code new From(...)} into {@code new To(...)} in every method. A subclass's own
     * {@code super(...)} call stays: an {@code invokespecial From.<init>} is only replaced when a
     * {@code new From} is waiting for it.
     */
    private boolean replaceInstantiations(String className, ClassNode node, List<Rebase> candidates) {
        boolean changed = false;
        for (Rebase replace : candidates) {
            boolean replaced = false;
            for (MethodNode method : node.methods) {
                int pendingNews = 0;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(replace.from())) {
                        type.desc = replace.to();
                        pendingNews++;
                        replaced = true;
                    } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.name.equals("<init>") && call.owner.equals(replace.from()) && pendingNews > 0) {
                        call.owner = replace.to();
                        pendingNews--;
                    }
                }
            }
            if (replaced) {
                reportOncePerNamespace(className, "instantiates " + replace.to() + " for " + replace.from());
                changed = true;
            }
        }
        return changed;
    }

    private boolean replaceIntConstants(String className, ClassNode node, List<IntConstant> candidates) {
        boolean changed = false;
        for (IntConstant constant : candidates) {
            boolean replaced = false;
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value && value == constant.from()) {
                        ldc.cst = constant.to();
                        replaced = true;
                    }
                }
            }
            if (replaced) {
                reportOncePerNamespace(className, constant.why());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Sets the interface flag on {@code invokestatic}/{@code invokespecial} method handles whose owner is
     * an interface. ASM 5.0's {@code Handle} had no such flag, so mods reobfuscated with it (SpecialSource
     * in ForgeGradle 1.x) refer to a lambda body in an interface, or to {@code Comparator::comparing}, with
     * a {@code CONSTANT_Methodref}. Java 8 accepted that; Java 9+ throws {@code IncompatibleClassChangeError}
     * ("Inconsistent constant pool data ... should be CONSTANT_InterfaceMethodRef").
     */
    private boolean fixInterfaceHandles(String className, ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    Handle bsm = interfaceHandle(node, indy.bsm);
                    changed |= bsm != indy.bsm;
                    indy.bsm = bsm;
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (indy.bsmArgs[i] instanceof Handle handle) {
                            Handle fixed = interfaceHandle(node, handle);
                            changed |= fixed != handle;
                            indy.bsmArgs[i] = fixed;
                        }
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                    Handle fixed = interfaceHandle(node, handle);
                    changed |= fixed != handle;
                    ldc.cst = fixed;
                }
            }
        }
        if (changed) {
            reportOncePerNamespace(className, "marked method handles to interface methods");
        }
        return changed;
    }

    /** {@code handle}, or a copy with the interface flag if it should have had it. */
    private Handle interfaceHandle(ClassNode node, Handle handle) {
        int tag = handle.getTag();
        if (handle.isInterface() || (tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_INVOKESPECIAL)) {
            return handle;
        }
        boolean isInterface = handle.getOwner().equals(node.name)
                ? (node.access & Opcodes.ACC_INTERFACE) != 0
                : interfaces.test(handle.getOwner());
        return isInterface ? new Handle(tag, handle.getOwner(), handle.getName(), handle.getDesc(), true) : handle;
    }

    /** Exact type renames first, then package prefixes. */
    private static final class Renamer extends Remapper {
        private final Map<String, String> types;
        private final List<PackageRename> packages;

        Renamer(Map<String, String> types, List<PackageRename> packages) {
            this.types = types;
            this.packages = packages;
        }

        @Override
        public String map(String internalName) {
            String type = types.get(internalName);
            if (type != null) {
                return type;
            }
            for (PackageRename rename : packages) {
                if (internalName.startsWith(rename.from())) {
                    return rename.to() + internalName.substring(rename.from().length());
                }
            }
            return internalName;
        }
    }

    /** For rules that hit hundreds of classes of one mod or library: only its first class is reported. */
    private void reportOncePerNamespace(String className, String what) {
        String namespace = namespace(className);
        if (reported.add(what + " " + namespace)) {
            listener.changed(className, what + " (logged once for " + namespace + ".*)");
        }
    }

    /**
     * At most the first two package segments of a binary class name: {@code org.spongepowered} for
     * {@code org.spongepowered.asm.Foo}, {@code optifine} for {@code optifine.Foo}.
     */
    static String namespace(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        int first = className.indexOf('.');
        int second = className.indexOf('.', first + 1);
        return className.substring(0, second < 0 || second > lastDot ? lastDot : second);
    }

    /**
     * Makes {@code node} extend the rebase target if it extends a rebased class: its superclass, and the
     * {@code super(...)} call in each constructor. {@code new X(...)} inside a constructor is left alone:
     * an {@code invokespecial X.<init>} belongs to a {@code new X} when one is still waiting for it.
     */
    private boolean rebase(String className, ClassNode node, List<Rebase> candidates) {
        for (Rebase rebase : candidates) {
            // The rebase target itself extends the rebased class.
            if (!rebase.from().equals(node.superName) || rebase.to().equals(node.name)) {
                continue;
            }
            node.superName = rebase.to();
            for (MethodNode method : node.methods) {
                if (!method.name.equals("<init>")) {
                    continue;
                }
                int pendingNews = 0;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(rebase.from())) {
                        pendingNews++;
                    } else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.name.equals("<init>") && call.owner.equals(rebase.from())) {
                        if (pendingNews > 0) {
                            pendingNews--;
                        } else {
                            call.owner = rebase.to();
                        }
                    }
                }
            }
            listener.changed(className, "rebased from " + rebase.from() + " onto " + rebase.to());
            return true;
        }
        return false;
    }

    private static final byte[] LAMBDA_METAFACTORY = utf8("java/lang/invoke/LambdaMetafactory");

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Collects the rules; type names are internal names ({@code a/b/C}), patch targets binary names. */
    public static final class Builder {
        private final List<TypeRename> typeRenames = new ArrayList<>();
        private final List<PackageRename> packageRenames = new ArrayList<>();
        private final List<StringRename> stringRenames = new ArrayList<>();
        private final List<RedirectSpec> redirects = new ArrayList<>();
        private final Map<String, String> rebases = new HashMap<>();
        private Predicate<String> interfaces;
        private final Map<String, String> instantiations = new HashMap<>();
        private final List<IntConstantSpec> intConstants = new ArrayList<>();
        private final List<CallTable> callTables = new ArrayList<>();
        private final List<ArgumentFilterSpec> argumentFilters = new ArrayList<>();
        private final Map<String, List<ClassPatch>> patches = new HashMap<>();
        private Listener listener = (className, what) -> {
        };

        private Builder() {
        }

        /** Every reference to type {@code from} becomes {@code to}. Nested types need their own rename. */
        public Builder renameType(String from, String to) {
            typeRenames.add(new TypeRename(from, to, Needles.of(List.of(from), List.of())));
            return this;
        }

        /**
         * Every reference to a type in package {@code from} (an internal name prefix ending in {@code /},
         * subpackages included) becomes the same type under {@code to}, except in classes of {@code from}
         * itself. Redirects registered for types under {@code to} also apply to the old spelling.
         */
        public Builder renamePackage(String from, String to) {
            return renamePackage(from, to, () -> true);
        }

        /**
         * {@link #renamePackage(String, String)}, if {@code when} says so. It's asked once, when the first
         * class that would be renamed is transformed, so it can look at what's on the class path by then.
         */
        public Builder renamePackage(String from, String to, BooleanSupplier when) {
            if (!from.endsWith("/") || !to.endsWith("/")) {
                throw new IllegalArgumentException("package prefixes end in '/': " + from + ", " + to);
            }
            packageRenames.add(new PackageRename(from, to, utf8(from), when));
            return this;
        }

        /**
         * Every string constant equal to {@code from} becomes {@code to}, in classes that also contain
         * one of {@code markers} (any class, if there are none).
         */
        public Builder renameString(String from, String to, String... markers) {
            stringRenames.add(new StringRename(from, to, Needles.of(List.of(from), List.of(markers))));
            return this;
        }

        /**
         * Calls to {@code owner.name descriptor} become calls to the static method {@code target.name},
         * in classes that also contain one of {@code markers} (any class, if there are none). For instance
         * methods the target takes the receiver as its first parameter.
         */
        public Builder redirect(String owner, String name, String descriptor, String target, String... markers) {
            redirects.add(new RedirectSpec(new Member(owner, name, descriptor), target, List.of(markers)));
            return this;
        }

        /**
         * Classes that extend {@code from} extend {@code to} instead, which must extend {@code from} and
         * declare every constructor {@code from} has, with the same descriptors.
         */
        public Builder rebase(String from, String to) {
            rebases.put(from, to);
            return this;
        }

        /**
         * {@code new from(...)} becomes {@code new to(...)}, where {@code to} extends {@code from} and declares
         * the constructors that are called, with the same descriptors. Subclasses of {@code from} are left
         * alone.
         */
        public Builder replaceInstantiations(String from, String to) {
            instantiations.put(from, to);
            return this;
        }

        /**
         * Int constants {@code from} loaded with {@code ldc} become {@code to}, in classes that reference
         * type {@code marker} (in any spelling, see {@link #renamePackage}); {@code why} is what's logged.
         */
        public Builder replaceIntConstant(int from, int to, String marker, String why) {
            intConstants.add(new IntConstantSpec(from, to, marker, why));
            return this;
        }

        /**
         * Fixes method handles to interface methods that lack the interface flag, in classes with lambdas
         * or method references. {@code isInterface} answers for owners other than the class itself
         * (internal names), and must not load classes.
         */
        public Builder fixInterfaceHandles(Predicate<String> isInterface) {
            this.interfaces = isInterface;
            return this;
        }

        /**
         * Calls to {@code owner.name descriptor} (a constructor too, including a subclass's {@code super(...)})
         * pass their first argument through the static {@code filterOwner.filterName}, which takes and
         * returns that argument's type. Only for calls with one argument, or two of which the second is one
         * stack slot.
         */
        public Builder filterFirstArgument(String owner, String name, String descriptor, String filterOwner, String filterName) {
            argumentFilters.add(new ArgumentFilterSpec(new Member(owner, name, descriptor), filterOwner, filterName));
            return this;
        }

        /** Applies the rewrites in {@code table}, in classes that contain its gate. */
        public Builder calls(CallTable table) {
            callTables.add(table);
            return this;
        }

        /** Applies {@code patch} to the class with binary name {@code className}. */
        public Builder patch(String className, ClassPatch patch) {
            patches.computeIfAbsent(className, k -> new ArrayList<>()).add(patch);
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public CompatTransformer build() {
            return new CompatTransformer(this);
        }
    }
}
