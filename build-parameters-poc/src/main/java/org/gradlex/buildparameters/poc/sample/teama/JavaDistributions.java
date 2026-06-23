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
package org.gradlex.buildparameters.poc.sample.teama;

import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradlex.buildparameters.poc.schema.ExternalGroupType;

/**
 * The public surface "team A" exposes so other teams can compose this group into their build parameters.
 *
 * <ul>
 *     <li>{@link #javaDistribution()} returns the descriptor a consumer mounts:
 *         {@code group("buildJvm", JavaDistributions.javaDistribution())}.</li>
 *     <li>{@link #from(ProviderFactory, String)} is the factory the generated accessor calls; it binds an
 *         instance to the mount's dotted key prefix (e.g. {@code "buildJvm."}).</li>
 * </ul>
 */
public final class JavaDistributions {

    private JavaDistributions() {
    }

    /** Descriptor for mounting this group into someone else's {@code buildParameters { }}. */
    public static ExternalGroupType javaDistribution() {
        return new ExternalGroupType(
            JavaDistribution.class.getName(),
            JavaDistributions.class.getName(),
            "from");
    }

    /** Factory invoked by the generated accessor; {@code keyPrefix} is e.g. {@code "buildJvm."}. */
    public static JavaDistribution from(ProviderFactory providers, String keyPrefix) {
        return new DefaultJavaDistribution(providers, keyPrefix);
    }

    private static final class DefaultJavaDistribution implements JavaDistribution {
        private final ProviderFactory providers;
        private final String keyPrefix;

        DefaultJavaDistribution(ProviderFactory providers, String keyPrefix) {
            this.providers = providers;
            this.keyPrefix = keyPrefix;
        }

        @Override
        public Provider<String> getVersion() {
            return providers.gradleProperty(keyPrefix + "version").orElse("17");
        }

        @Override
        public Provider<String> getVendor() {
            return providers.gradleProperty(keyPrefix + "vendor").orElse("adoptium");
        }
    }
}
