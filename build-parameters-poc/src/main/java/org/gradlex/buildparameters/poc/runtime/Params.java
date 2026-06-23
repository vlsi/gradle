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
package org.gradlex.buildparameters.poc.runtime;

import org.gradle.api.Transformer;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import java.io.Serializable;

/**
 * Runtime support invoked by the generated accessor classes.
 *
 * <p>Generated group classes are <em>prefix-parameterized</em>: each instance carries the dotted key
 * prefix of the mount point it was created at (e.g. {@code "buildJvm."}), and every getter resolves its
 * gradle property as {@code prefix + localKey}. This is what lets a single generated type (say
 * {@code JavaDistribution}) be mounted at several places ({@code buildJvm}, {@code testJvm}) and still read
 * the right, distinct properties. Keeping the prefix concatenation here (normal javac-compiled code) keeps
 * the emitted bytecode to a single {@code invokestatic} per getter.</p>
 *
 * <p>The provider chain is built from <em>named, {@link Serializable}</em> types so the resulting
 * {@link Provider} survives Configuration Cache store/load.</p>
 */
public final class Params {

    private Params() {
    }

    /** The key prefix for a child group mounted as {@code mountName} under {@code parentPrefix}. */
    public static String childPrefix(String parentPrefix, String mountName) {
        return parentPrefix + mountName + ".";
    }

    public static Provider<String> string(ProviderFactory providers, String prefix, String key, String defaultValue) {
        Provider<String> value = providers.gradleProperty(prefix + key);
        return defaultValue == null ? value : value.orElse(defaultValue);
    }

    public static Provider<Integer> integer(ProviderFactory providers, String prefix, String key, Integer defaultValue) {
        Provider<Integer> value = providers.gradleProperty(prefix + key).map(new StringToInteger());
        return defaultValue == null ? value : value.orElse(defaultValue);
    }

    public static Provider<Boolean> bool(ProviderFactory providers, String prefix, String key, Boolean defaultValue) {
        Provider<Boolean> value = providers.gradleProperty(prefix + key).map(new StringToBoolean());
        return defaultValue == null ? value : value.orElse(defaultValue);
    }

    /**
     * A named, serializable {@link Transformer} (as opposed to {@code Integer::valueOf}, whose synthetic
     * lambda class would not be cleanly serializable by the Configuration Cache).
     */
    public static final class StringToInteger implements Transformer<Integer, String>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Integer transform(String s) {
            return Integer.valueOf(s.trim());
        }
    }

    public static final class StringToBoolean implements Transformer<Boolean, String>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Boolean transform(String s) {
            return Boolean.valueOf(s.trim());
        }
    }
}
