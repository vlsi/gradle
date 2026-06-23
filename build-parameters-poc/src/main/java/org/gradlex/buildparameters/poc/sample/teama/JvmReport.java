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

/**
 * A separately-compiled consumer of {@link JavaDistribution} — stands in for build logic team A also
 * ships (a task, a convention, etc.). A build script can pass {@code buildParameters.buildJvm} straight
 * into this because the accessor's type <em>is</em> team A's {@code JavaDistribution}.
 */
public final class JvmReport {

    private JvmReport() {
    }

    public static Provider<String> describe(JavaDistribution distribution) {
        return distribution.getVersion().zip(distribution.getVendor(), (version, vendor) -> vendor + "@" + version);
    }
}
