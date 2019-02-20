/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.snapshot.impl;

import org.gradle.api.internal.provider.DefaultListProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.internal.isolation.Isolatable;

import javax.annotation.Nullable;
import java.util.List;

class IsolatedListProperty extends IsolatedProvider {
    private final Class<?> type;

    public IsolatedListProperty(Class<?> type, Isolatable<?> value) {
        super(value);
        this.type = type;
    }

    @Nullable
    @Override
    public ListProperty<Object> isolate() {
        DefaultListProperty<Object> property = new DefaultListProperty<>((Class<Object>) type);
        property.set((List<?>) value.isolate());
        return property;
    }
}