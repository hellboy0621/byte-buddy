/*
 * Copyright 2014 - Present Rafael Winterhalter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bytebuddy.implementation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.build.HashCodeAndEqualsPlugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.privilege.SetAccessibleAction;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementations of this interface explicitly initialize a loaded type. Usually, such implementations inject runtime
 * context into an instrumented type which cannot be defined by the means of the Java class file format.
 */
public interface LoadedTypeInitializer {

    /**
     * Callback that is invoked on the creation of an instrumented type. If the loaded type initializer is alive, this
     * method should be implemented empty instead of throwing an exception.
     *
     * @param type The manifestation of the instrumented type.
     */
    void onLoad(Class<?> type);

    /**
     * Indicates if this initializer is alive and needs to be invoked. This is only meant as a mark. A loaded type
     * initializer that is not alive might still be called and must therefore not throw an exception but rather
     * provide an empty implementation.
     *
     * @return {@code true} if this initializer is alive.
     */
    boolean isAlive();

    /**
     * A loaded type initializer that does not do anything.
     */
    enum NoOp implements LoadedTypeInitializer {

        /**
         * The singleton instance.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         */
        public void onLoad(Class<?> type) {
            /* do nothing */
        }

        /**
         * {@inheritDoc}
         */
        public boolean isAlive() {
            return false;
        }
    }

    /**
     * A type initializer for setting a value for a static field.
     */
    @HashCodeAndEqualsPlugin.Enhance
    class ForStaticField implements LoadedTypeInitializer, Serializable {

        /**
         * This class's serial version UID.
         */
        private static final long serialVersionUID = 1L;

        /**
         * A value for accessing a static field.
         */
        private static final Object STATIC_FIELD = null;

        /**
         * The name of the field.
         */
        private final String fieldName;

        /**
         * The value of the field.
         */
        private final Object value;

        /**
         * Creates a new {@link LoadedTypeInitializer} for setting a static field.
         *
         * @param fieldName the name of the field.
         * @param value     The value to be set.
         */
        public ForStaticField(String fieldName, Object value) {
            this.fieldName = fieldName;
            this.value = value;
        }

        /**
         * {@inheritDoc}
         */
        public void onLoad(Class<?> type) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!Modifier.isPublic(field.getModifiers())
                        || !Modifier.isPublic(field.getDeclaringClass().getModifiers())
                        || JavaModule.isSupported()
                        && !JavaModule.ofType(type).isExported(new TypeDescription.ForLoadedType(type).getPackage(), JavaModule.ofType(ForStaticField.class))) {
                    AccessController.doPrivileged(new SetAccessibleAction<Field>(field));
                }
                field.set(STATIC_FIELD, value);
            } catch (IllegalAccessException exception) {
                throw new IllegalArgumentException("Cannot access " + fieldName + " from " + type, exception);
            } catch (NoSuchFieldException exception) {
                throw new IllegalStateException("There is no field " + fieldName + " defined on " + type, exception);
            }
        }

        /**
         * {@inheritDoc}
         */
        public boolean isAlive() {
            return true;
        }
    }

    /**
     * A compound loaded type initializer that combines several type initializers.
     */
    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Serialization is considered opt-in for a rare use case")
    @HashCodeAndEqualsPlugin.Enhance
    class Compound implements LoadedTypeInitializer, Serializable {

        /**
         * This class's serial version UID.
         */
        private static final long serialVersionUID = 1L;

        /**
         * The loaded type initializers that are represented by this compound type initializer.
         */
        private final List<LoadedTypeInitializer> loadedTypeInitializers;

        /**
         * Creates a new compound loaded type initializer.
         *
         * @param loadedTypeInitializer A number of loaded type initializers in their invocation order.
         */
        public Compound(LoadedTypeInitializer... loadedTypeInitializer) {
            this(Arrays.asList(loadedTypeInitializer));
        }

        /**
         * Creates a new compound loaded type initializer.
         *
         * @param loadedTypeInitializers A number of loaded type initializers in their invocation order.
         */
        public Compound(List<? extends LoadedTypeInitializer> loadedTypeInitializers) {
            this.loadedTypeInitializers = new ArrayList<LoadedTypeInitializer>();
            for (LoadedTypeInitializer loadedTypeInitializer : loadedTypeInitializers) {
                if (loadedTypeInitializer instanceof Compound) {
                    this.loadedTypeInitializers.addAll(((Compound) loadedTypeInitializer).loadedTypeInitializers);
                } else if (!(loadedTypeInitializer instanceof NoOp)) {
                    this.loadedTypeInitializers.add(loadedTypeInitializer);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        public void onLoad(Class<?> type) {
            for (LoadedTypeInitializer loadedTypeInitializer : loadedTypeInitializers) {
                loadedTypeInitializer.onLoad(type);
            }
        }

        /**
         * {@inheritDoc}
         */
        public boolean isAlive() {
            for (LoadedTypeInitializer loadedTypeInitializer : loadedTypeInitializers) {
                if (loadedTypeInitializer.isAlive()) {
                    return true;
                }
            }
            return false;
        }
    }
}
