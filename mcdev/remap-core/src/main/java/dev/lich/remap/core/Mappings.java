package dev.lich.remap.core;

import org.objectweb.asm.Type;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Names in one namespace mapped to another (obf → SRG, MCP → SRG, ...): classes by internal name, fields
 * by owner and name, methods by owner, name and descriptor, all keyed in the source namespace. Members
 * are only listed on the class that declares them; {@link MappingRemapper} finds inherited ones.
 */
public final class Mappings {
    private final Map<String, String> classes;
    private final Map<String, Map<String, String>> fields;
    private final Map<String, Map<String, String>> methods;

    private Mappings(Map<String, String> classes, Map<String, Map<String, String>> fields,
                     Map<String, Map<String, String>> methods) {
        this.classes = classes;
        this.fields = fields;
        this.methods = methods;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The class's name in the target namespace, or {@code null} if it isn't mapped. */
    public String mapClass(String internalName) {
        return classes.get(internalName);
    }

    /** The field's target name if {@code owner} itself declares a mapping for it, otherwise {@code null}. */
    public String field(String owner, String name) {
        Map<String, String> owned = fields.get(owner);
        return owned == null ? null : owned.get(name);
    }

    /** The method's target name if {@code owner} itself declares a mapping for it, otherwise {@code null}. */
    public String method(String owner, String name, String descriptor) {
        Map<String, String> owned = methods.get(owner);
        return owned == null ? null : owned.get(name + descriptor);
    }

    /** Whether the class is mapped or has mapped members, i.e. whether it belongs to the mapped code base. */
    public boolean knows(String internalName) {
        return classes.containsKey(internalName) || fields.containsKey(internalName) || methods.containsKey(internalName);
    }

    /** Source class names, in no particular order. */
    public Set<String> classNames() {
        return Collections.unmodifiableSet(classes.keySet());
    }

    /** Source owner → (source name → target name). */
    public Map<String, Map<String, String>> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** Source owner → (source name + source descriptor → target name). */
    public Map<String, Map<String, String>> methods() {
        return Collections.unmodifiableMap(methods);
    }

    /** A source method descriptor (or field descriptor) with its class names mapped to the target namespace. */
    public String mapDescriptor(String descriptor) {
        if (descriptor.startsWith("(")) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = mapType(arguments[i]);
            }
            return Type.getMethodDescriptor(mapType(Type.getReturnType(descriptor)), arguments);
        }
        return mapType(Type.getType(descriptor)).getDescriptor();
    }

    private Type mapType(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> {
                String mapped = classes.get(type.getInternalName());
                yield mapped == null ? type : Type.getObjectType(mapped);
            }
            case Type.ARRAY -> {
                String element = mapType(type.getElementType()).getDescriptor();
                yield Type.getType("[".repeat(type.getDimensions()) + element);
            }
            default -> type;
        };
    }

    /**
     * The same mappings in the other direction. Unmapped classes in descriptors keep their names, so this
     * only round-trips when no two source names map to the same target name.
     */
    public Mappings inverse() {
        Builder inverse = builder();
        classes.forEach((source, target) -> inverse.mapClass(target, source));
        fields.forEach((owner, owned) -> {
            String targetOwner = classes.getOrDefault(owner, owner);
            owned.forEach((source, target) -> inverse.mapField(targetOwner, target, source));
        });
        methods.forEach((owner, owned) -> {
            String targetOwner = classes.getOrDefault(owner, owner);
            owned.forEach((key, target) -> {
                int paren = key.indexOf('(');
                inverse.mapMethod(targetOwner, target, mapDescriptor(key.substring(paren)), key.substring(0, paren));
            });
        });
        return inverse.build();
    }

    /**
     * These mappings with their target member names passed through {@code fieldNames} and
     * {@code methodNames}: for example obf → SRG with MCP's names for the SRG ones gives obf → MCP.
     */
    public Mappings renameTargets(UnaryOperator<String> fieldNames, UnaryOperator<String> methodNames) {
        Builder renamed = builder();
        classes.forEach(renamed::mapClass);
        fields.forEach((owner, owned) -> owned.forEach((source, target) ->
                renamed.mapField(owner, source, fieldNames.apply(target))));
        methods.forEach((owner, owned) -> owned.forEach((key, target) -> {
            int paren = key.indexOf('(');
            renamed.mapMethod(owner, key.substring(0, paren), key.substring(paren), methodNames.apply(target));
        }));
        return renamed.build();
    }

    /** Collects mappings; the last mapping of a name wins. */
    public static final class Builder {
        private final Map<String, String> classes = new HashMap<>();
        private final Map<String, Map<String, String>> fields = new HashMap<>();
        private final Map<String, Map<String, String>> methods = new HashMap<>();

        private Builder() {
        }

        public Builder mapClass(String source, String target) {
            classes.put(source, target);
            return this;
        }

        public Builder mapField(String owner, String source, String target) {
            fields.computeIfAbsent(owner, o -> new HashMap<>()).put(source, target);
            return this;
        }

        public Builder mapMethod(String owner, String source, String descriptor, String target) {
            methods.computeIfAbsent(owner, o -> new HashMap<>()).put(source + descriptor, target);
            return this;
        }

        /** Adds every mapping of {@code other}, replacing any of these with the same source name. */
        public Builder addAll(Mappings other) {
            classes.putAll(other.classes);
            other.fields.forEach((owner, owned) -> fields.computeIfAbsent(owner, o -> new HashMap<>()).putAll(owned));
            other.methods.forEach((owner, owned) -> methods.computeIfAbsent(owner, o -> new HashMap<>()).putAll(owned));
            return this;
        }

        public Mappings build() {
            return new Mappings(Map.copyOf(classes), copy(fields), copy(methods));
        }

        private static Map<String, Map<String, String>> copy(Map<String, Map<String, String>> members) {
            Map<String, Map<String, String>> copy = new HashMap<>(members.size() * 2);
            members.forEach((owner, owned) -> copy.put(owner, Map.copyOf(owned)));
            return Collections.unmodifiableMap(copy);
        }
    }
}
