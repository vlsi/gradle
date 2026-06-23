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
package org.gradlex.buildparameters.poc.codegen;

import org.gradlex.buildparameters.poc.runtime.Params;
import org.gradlex.buildparameters.poc.schema.BuildParametersSchema;
import org.gradlex.buildparameters.poc.schema.BuildParametersSchema.GroupType;
import org.gradlex.buildparameters.poc.schema.BuildParametersSchema.Leaf;
import org.gradlex.buildparameters.poc.schema.BuildParametersSchema.Mount;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the accessor classes as bytecode using ASM — no javac, no extra Gradle project.
 *
 * <p>One class is generated per {@link GroupType}. Each class is <em>prefix-parameterized</em>: it holds a
 * {@code ProviderFactory} and a dotted key {@code prefix}, so the same class can be mounted at several
 * places and still resolve distinct properties. Leaf getters are a single {@code invokestatic} into
 * {@link Params}; mount getters new up the child class with {@code Params.childPrefix(prefix, name)}.</p>
 *
 * <p>Every method body is straight-line code, so {@code COMPUTE_MAXS} suffices (no stack-map frames).</p>
 */
public final class AsmAccessorGenerator {

    private static final String PROVIDER = "org/gradle/api/provider/Provider";
    private static final String PROVIDER_FACTORY = "org/gradle/api/provider/ProviderFactory";
    private static final String PROVIDER_FACTORY_DESC = "L" + PROVIDER_FACTORY + ";";
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final String PARAMS = Type.getInternalName(Params.class);

    /**
     * @return map of binary class name (e.g. {@code org.gradlex.buildparameters.generated.BuildParameters})
     *         to its bytecode.
     */
    public Map<String, byte[]> generate(BuildParametersSchema schema) {
        Map<String, GroupType> byName = new HashMap<>();
        for (GroupType type : schema.getAllTypes()) {
            GroupType previous = byName.put(type.getSimpleClassName(), type);
            if (previous != null && previous != type) {
                throw new IllegalStateException("Two parameter groups generate the same class name '"
                    + type.getSimpleClassName() + "'. Give the groups distinct names, or factor the shared "
                    + "shape into a reusable groupType(\"" + type.getSimpleClassName() + "\") { ... }.");
            }
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (GroupType type : schema.getAllTypes()) {
            classes.put(type.getFqcn(), generateClass(type));
        }
        return classes;
    }

    private byte[] generateClass(GroupType type) {
        String internalName = type.getFqcn().replace('.', '/');

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "providers", PROVIDER_FACTORY_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "prefix", STRING_DESC, null, null).visitEnd();

        generateConstructor(cw, internalName, type.isRoot());

        for (Leaf leaf : type.getLeaves()) {
            generateLeafGetter(cw, internalName, leaf);
        }
        for (Mount mount : type.getMounts()) {
            generateMountGetter(cw, internalName, mount);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The root takes just {@code (ProviderFactory)} and uses an empty prefix (so it can be instantiated by
     * the plugin uniformly); every other class takes {@code (ProviderFactory, String prefix)}.
     */
    private void generateConstructor(ClassWriter cw, String internalName, boolean root) {
        String descriptor = root ? "(" + PROVIDER_FACTORY_DESC + ")V" : "(" + PROVIDER_FACTORY_DESC + STRING_DESC + ")V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "providers", PROVIDER_FACTORY_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (root) {
            mv.visitLdcInsn("");
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
        }
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "prefix", STRING_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateLeafGetter(ClassWriter cw, String internalName, Leaf leaf) {
        String elementType; // boxed element type for the Provider<T> signature
        String paramsMethod;
        String boxedDesc;
        switch (leaf.getType()) {
            case STRING:
                elementType = "java/lang/String";
                paramsMethod = "string";
                boxedDesc = STRING_DESC;
                break;
            case INTEGER:
                elementType = "java/lang/Integer";
                paramsMethod = "integer";
                boxedDesc = "Ljava/lang/Integer;";
                break;
            case BOOLEAN:
                elementType = "java/lang/Boolean";
                paramsMethod = "bool";
                boxedDesc = "Ljava/lang/Boolean;";
                break;
            default:
                throw new IllegalStateException("Unknown type: " + leaf.getType());
        }
        String paramsDesc = "(" + PROVIDER_FACTORY_DESC + STRING_DESC + STRING_DESC + boxedDesc + ")L" + PROVIDER + ";";
        String signature = "()L" + PROVIDER + "<L" + elementType + ";>;";

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName(leaf.getName()), "()L" + PROVIDER + ";", signature, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "providers", PROVIDER_FACTORY_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "prefix", STRING_DESC);
        mv.visitLdcInsn(leaf.getName());
        pushDefault(mv, leaf);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PARAMS, paramsMethod, paramsDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void pushDefault(MethodVisitor mv, Leaf leaf) {
        Object def = leaf.getDefaultValue();
        if (def == null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }
        switch (leaf.getType()) {
            case STRING:
                mv.visitLdcInsn(def); // already a String
                break;
            case INTEGER:
                mv.visitLdcInsn(((Number) def).intValue());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case BOOLEAN:
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean",
                    ((Boolean) def) ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
                break;
            default:
                throw new IllegalStateException("Unknown type: " + leaf.getType());
        }
    }

    private void generateMountGetter(ClassWriter cw, String ownerInternalName, Mount mount) {
        String childInternal = mount.getType().getFqcn().replace('.', '/');
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName(mount.getName()), "()L" + childInternal + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, childInternal);
        mv.visitInsn(Opcodes.DUP);
        // arg 1: this.providers
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, "providers", PROVIDER_FACTORY_DESC);
        // arg 2: Params.childPrefix(this.prefix, "<mountName>")
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, "prefix", STRING_DESC);
        mv.visitLdcInsn(mount.getName());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PARAMS, "childPrefix",
            "(" + STRING_DESC + STRING_DESC + ")" + STRING_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, childInternal, "<init>",
            "(" + PROVIDER_FACTORY_DESC + STRING_DESC + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static String getterName(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }
}
