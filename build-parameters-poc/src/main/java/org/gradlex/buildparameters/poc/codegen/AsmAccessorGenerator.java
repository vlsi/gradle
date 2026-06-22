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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the accessor classes as bytecode using ASM — no javac, no extra Gradle project.
 *
 * <p>For each group node a class is generated with:
 * <ul>
 *     <li>a {@code private final ProviderFactory providers} field,</li>
 *     <li>a {@code (ProviderFactory)} constructor,</li>
 *     <li>a {@code Provider<T> getXxx()} for each leaf, whose body is a single {@code invokestatic} into
 *         {@link Params}, and</li>
 *     <li>a {@code GroupType getXxx()} for each nested group, whose body simply news up the child class.</li>
 * </ul>
 * Every method body is straight-line code, so no stack-map frames are required and {@code COMPUTE_MAXS}
 * is sufficient (avoiding ASM's {@code COMPUTE_FRAMES} class-hierarchy lookups for not-yet-loaded types).
 */
public final class AsmAccessorGenerator {

    private static final String PROVIDER = "org/gradle/api/provider/Provider";
    private static final String PROVIDER_FACTORY = "org/gradle/api/provider/ProviderFactory";
    private static final String PROVIDER_FACTORY_DESC = "L" + PROVIDER_FACTORY + ";";
    private static final String PARAMS = Type.getInternalName(Params.class);

    /**
     * @return map of binary class name (e.g. {@code org.gradlex.buildparameters.generated.BuildParameters})
     *         to its bytecode.
     */
    public Map<String, byte[]> generate(BuildParametersSchema root) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        generateClass(root, classes);
        return classes;
    }

    private void generateClass(BuildParametersSchema node, Map<String, byte[]> out) {
        String internalName = node.getFqcn().replace('.', '/');

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "providers", PROVIDER_FACTORY_DESC, null, null).visitEnd();

        generateConstructor(cw, internalName);

        for (BuildParametersSchema.Leaf leaf : node.getLeaves()) {
            generateLeafGetter(cw, internalName, leaf);
        }
        for (BuildParametersSchema child : node.getGroups()) {
            generateGroupGetter(cw, internalName, child);
            generateClass(child, out); // recurse
        }

        cw.visitEnd();
        out.put(node.getFqcn(), cw.toByteArray());
    }

    private void generateConstructor(ClassWriter cw, String internalName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + PROVIDER_FACTORY_DESC + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "providers", PROVIDER_FACTORY_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateLeafGetter(ClassWriter cw, String internalName, BuildParametersSchema.Leaf leaf) {
        String getterName = getterName(leaf.getName());
        String elementType; // boxed element type for the Provider<T> signature
        String paramsMethod;
        String paramsDesc;
        switch (leaf.getType()) {
            case STRING:
                elementType = "java/lang/String";
                paramsMethod = "string";
                paramsDesc = "(" + PROVIDER_FACTORY_DESC + "Ljava/lang/String;Ljava/lang/String;)L" + PROVIDER + ";";
                break;
            case INTEGER:
                elementType = "java/lang/Integer";
                paramsMethod = "integer";
                paramsDesc = "(" + PROVIDER_FACTORY_DESC + "Ljava/lang/String;Ljava/lang/Integer;)L" + PROVIDER + ";";
                break;
            case BOOLEAN:
                elementType = "java/lang/Boolean";
                paramsMethod = "bool";
                paramsDesc = "(" + PROVIDER_FACTORY_DESC + "Ljava/lang/String;Ljava/lang/Boolean;)L" + PROVIDER + ";";
                break;
            default:
                throw new IllegalStateException("Unknown type: " + leaf.getType());
        }

        // Generic return signature so Kotlin/Groovy callers see Provider<String>, Provider<Integer>, ...
        String signature = "()L" + PROVIDER + "<L" + elementType + ";>;";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName, "()L" + PROVIDER + ";", signature, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "providers", PROVIDER_FACTORY_DESC);
        mv.visitLdcInsn(leaf.getKey());
        pushDefault(mv, leaf);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PARAMS, paramsMethod, paramsDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void pushDefault(MethodVisitor mv, BuildParametersSchema.Leaf leaf) {
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

    private void generateGroupGetter(ClassWriter cw, String ownerInternalName, BuildParametersSchema child) {
        String childInternal = child.getFqcn().replace('.', '/');
        String getterName = getterName(child.getLocalName());
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName, "()L" + childInternal + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, childInternal);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, "providers", PROVIDER_FACTORY_DESC);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, childInternal, "<init>", "(" + PROVIDER_FACTORY_DESC + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static String getterName(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }
}
