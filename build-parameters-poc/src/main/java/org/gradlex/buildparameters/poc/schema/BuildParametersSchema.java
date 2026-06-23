/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradlex.buildparameters.poc.schema;

import org.gradle.api.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings-time DSL and schema model.
 *
 * <p>The model separates a group <em>type</em> ({@link GroupType}, which becomes one generated class) from
 * its <em>mount points</em> ({@link Mount}). An inline {@code group("database") { ... }} creates an
 * anonymous type used once; a {@code groupType("JavaDistribution") { ... }} creates a <em>reusable</em>
 * type that can be mounted at several places via {@code group("buildJvm", javaDistribution)}. Because the
 * generated classes are prefix-parameterized (see {@code AsmAccessorGenerator}), the same type can be
 * reused at multiple mounts and still read distinct, correctly-prefixed properties.</p>
 *
 * <p>This same class is the builder for both the root and every nested/reusable type; the {@link #type}
 * field is the type currently being configured.</p>
 */
public class BuildParametersSchema {

    /** Package for all generated accessor classes. */
    public static final String GENERATED_PACKAGE = "org.gradlex.buildparameters.generated";
    /** Simple name of the generated root accessor class. */
    public static final String ROOT_SIMPLE_NAME = "BuildParameters";

    /** A leaf parameter: a getter on the enclosing group's generated class. */
    public static final class Leaf {
        private final String name;
        private final ParameterType type;
        private final Object defaultValue;

        Leaf(String name, ParameterType type, Object defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public ParameterType getType() {
            return type;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }
    }

    /** A mount: exposes a group under {@code name} on the enclosing group. The group is either an internal
     * generated {@link GroupType} or an {@link ExternalGroupType} supplied by another artifact. */
    public static final class Mount {
        private final String name;
        private final GroupType internalType;
        private final ExternalGroupType externalType;

        Mount(String name, GroupType internalType, ExternalGroupType externalType) {
            this.name = name;
            this.internalType = internalType;
            this.externalType = externalType;
        }

        public String getName() {
            return name;
        }

        public boolean isExternal() {
            return externalType != null;
        }

        /** Non-null when {@link #isExternal()} is false. */
        public GroupType getInternalType() {
            return internalType;
        }

        /** Non-null when {@link #isExternal()} is true. */
        public ExternalGroupType getExternalType() {
            return externalType;
        }
    }

    /** One generated class: a set of leaves and a set of nested mounts. May be reused at several mounts. */
    public static final class GroupType {
        private final String simpleClassName;
        private final boolean root;
        private final List<Leaf> leaves = new ArrayList<>();
        private final List<Mount> mounts = new ArrayList<>();

        GroupType(String simpleClassName, boolean root) {
            this.simpleClassName = simpleClassName;
            this.root = root;
        }

        public String getSimpleClassName() {
            return simpleClassName;
        }

        public String getFqcn() {
            return GENERATED_PACKAGE + "." + simpleClassName;
        }

        public boolean isRoot() {
            return root;
        }

        public List<Leaf> getLeaves() {
            return leaves;
        }

        public List<Mount> getMounts() {
            return mounts;
        }
    }

    private final GroupType type;
    private final List<GroupType> registry;

    public BuildParametersSchema() {
        this.registry = new ArrayList<>();
        this.type = new GroupType(ROOT_SIMPLE_NAME, true);
        registry.add(type);
    }

    private BuildParametersSchema(GroupType type, List<GroupType> registry) {
        this.type = type;
        this.registry = registry;
    }

    // --- DSL: leaves ---------------------------------------------------------------------------------

    public void string(String name) {
        type.leaves.add(new Leaf(name, ParameterType.STRING, null));
    }

    public void string(String name, String defaultValue) {
        type.leaves.add(new Leaf(name, ParameterType.STRING, defaultValue));
    }

    public void integer(String name) {
        type.leaves.add(new Leaf(name, ParameterType.INTEGER, null));
    }

    public void integer(String name, int defaultValue) {
        type.leaves.add(new Leaf(name, ParameterType.INTEGER, defaultValue));
    }

    public void bool(String name) {
        type.leaves.add(new Leaf(name, ParameterType.BOOLEAN, null));
    }

    public void bool(String name, boolean defaultValue) {
        type.leaves.add(new Leaf(name, ParameterType.BOOLEAN, defaultValue));
    }

    // --- DSL: groups ---------------------------------------------------------------------------------

    /** An inline group: an anonymous type used at this one mount. */
    public void group(String name, Action<? super BuildParametersSchema> action) {
        GroupType anonymous = defineType(capitalize(name), action);
        type.mounts.add(new Mount(name, anonymous, null));
    }

    /** Define a reusable group type that can be mounted at several places. */
    public GroupType groupType(String typeName, Action<? super BuildParametersSchema> action) {
        return defineType(typeName, action);
    }

    /** Mount a (typically reusable) group type under {@code name}. */
    public void group(String name, GroupType groupType) {
        type.mounts.add(new Mount(name, groupType, null));
    }

    /** Mount a group type owned by another artifact (cross-plugin composition). */
    public void group(String name, ExternalGroupType externalType) {
        type.mounts.add(new Mount(name, null, externalType));
    }

    private GroupType defineType(String simpleClassName, Action<? super BuildParametersSchema> action) {
        GroupType newType = new GroupType(simpleClassName, false);
        registry.add(newType);
        action.execute(new BuildParametersSchema(newType, registry));
        return newType;
    }

    // --- model accessors -----------------------------------------------------------------------------

    public boolean isEmpty() {
        return type.leaves.isEmpty() && type.mounts.isEmpty();
    }

    /** Every generated type reachable from the root, each present exactly once. */
    public List<GroupType> getAllTypes() {
        return registry;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
