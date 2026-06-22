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
 * The settings-time DSL and schema model. One instance is the root; nested {@link #group} calls create
 * child instances. The schema is the <em>shape</em> (names + types + defaults) of the build parameters and
 * is fully known by the end of settings evaluation — exactly like a version catalog.
 *
 * <p>Each node knows the simple name of the accessor class to generate for it and the fully-qualified
 * (dotted) Gradle property key for each leaf.</p>
 */
public class BuildParametersSchema {

    /** Package for all generated accessor classes. */
    public static final String GENERATED_PACKAGE = "org.gradlex.buildparameters.generated";
    /** Simple name of the generated root accessor class. */
    public static final String ROOT_SIMPLE_NAME = "BuildParameters";

    public static final class Leaf {
        private final String name;
        private final String key;
        private final ParameterType type;
        private final Object defaultValue;

        Leaf(String name, String key, ParameterType type, Object defaultValue) {
            this.name = name;
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public String getKey() {
            return key;
        }

        public ParameterType getType() {
            return type;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }
    }

    private final String prefix; // "" for root, "database", "database.replica", ...
    private final String localName; // last path segment, e.g. "replica"; "" for root
    private final String simpleClassName;
    private final List<Leaf> leaves = new ArrayList<>();
    private final List<BuildParametersSchema> groups = new ArrayList<>();

    public BuildParametersSchema() {
        this("", "", ROOT_SIMPLE_NAME);
    }

    private BuildParametersSchema(String prefix, String localName, String simpleClassName) {
        this.prefix = prefix;
        this.localName = localName;
        this.simpleClassName = simpleClassName;
    }

    // --- DSL -----------------------------------------------------------------------------------------

    public void string(String name) {
        string(name, null);
    }

    public void string(String name, String defaultValue) {
        leaves.add(new Leaf(name, key(name), ParameterType.STRING, defaultValue));
    }

    public void integer(String name) {
        leaves.add(new Leaf(name, key(name), ParameterType.INTEGER, null));
    }

    public void integer(String name, int defaultValue) {
        leaves.add(new Leaf(name, key(name), ParameterType.INTEGER, defaultValue));
    }

    public void bool(String name) {
        leaves.add(new Leaf(name, key(name), ParameterType.BOOLEAN, null));
    }

    public void bool(String name, boolean defaultValue) {
        leaves.add(new Leaf(name, key(name), ParameterType.BOOLEAN, defaultValue));
    }

    public void group(String name, Action<? super BuildParametersSchema> action) {
        BuildParametersSchema child = new BuildParametersSchema(key(name), name, classNameFor(key(name)));
        action.execute(child);
        groups.add(child);
    }

    // --- model accessors -----------------------------------------------------------------------------

    public String getSimpleClassName() {
        return simpleClassName;
    }

    /** The accessor (getter) name exposed to build scripts, e.g. {@code replica}. */
    public String getLocalName() {
        return localName;
    }

    public String getFqcn() {
        return GENERATED_PACKAGE + "." + simpleClassName;
    }

    public List<Leaf> getLeaves() {
        return leaves;
    }

    public List<BuildParametersSchema> getGroups() {
        return groups;
    }

    public boolean isEmpty() {
        return leaves.isEmpty() && groups.isEmpty();
    }

    private String key(String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }

    /** Derive a collision-free class name from a dotted path, e.g. {@code database.replica -> DatabaseReplica}. */
    private static String classNameFor(String dottedPath) {
        StringBuilder sb = new StringBuilder();
        for (String segment : dottedPath.split("\\.")) {
            if (!segment.isEmpty()) {
                sb.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
        }
        return sb.toString();
    }
}
