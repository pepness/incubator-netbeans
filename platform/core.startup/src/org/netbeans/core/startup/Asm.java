/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.core.startup;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * More complex patching code which requires Asm is placed here. It
 * is called by reflection for [@link PatchByteCode}.
 */
final class Asm {
    
    private static final Logger LOG = Logger.getLogger(Asm.class.getName());

    private static final String DESC_PATCHED_PUBLIC_ANNOTATION = "Lorg/openide/modules/PatchedPublic;";
    private static final String DESC_CTOR_ANNOTATION = "Lorg/openide/modules/ConstructorDelegate;";
    private static final String DESC_DEFAULT_CTOR = "()V";
    private static final String CONSTRUCTOR_NAME = "<init>"; // NOI18N
    
    private Asm() {
    }


    public static byte[] patch(
        byte[] data, String extender, ClassLoader theClassLoader
    ) throws IOException {

        LOG.log(Level.INFO, "patch(start)\t data: {0}" , data);
        LOG.log(Level.INFO, "patch()\t extender: {0}" , extender);
        LOG.log(Level.INFO, "patch()\t theClassLoader: {0}" , theClassLoader);

        // must analyze the extender class, as some annotations there may trigger
        ClassReader clr = new ClassReader(data);
        ClassWriter wr = new ClassWriter(clr, 0);
        ClassNode theClass = new ClassNode(Opcodes.ASM5);
        
        clr.accept(theClass, 0);
        
        MethodNode defCtor = null;
        
        String extInternalName = extender.replace(".", "/"); // NOI18N
        
        // patch the superclass
        theClass.superName = extInternalName;
        String resName = extInternalName + ".class"; // NOI18N

        LOG.log(Level.INFO, "patch()\t extInternalName: {0}" , extInternalName);
        LOG.log(Level.INFO, "patch()\t ClassNode theClass: {0}" , theClass);
        LOG.log(Level.INFO, "patch()\t ClassNode theClass.name: {0}" , theClass.name);
        LOG.log(Level.INFO, "patch()\t resName: {0}" , resName);
        
        try (InputStream istm = theClassLoader.getResourceAsStream(resName)) {
            if (istm == null) {
                throw new IOException("Could not find classfile for extender class"); // NOI18N
            }
            ClassReader extenderReader = new ClassReader(istm);
            ClassNode extenderClass = new ClassNode(Opcodes.ASM5);
            extenderReader.accept(extenderClass, ClassReader.SKIP_FRAMES);

            LOG.log(Level.INFO, "patch()\t ClassReader extenderReader: {0}" , extenderReader);
            LOG.log(Level.INFO, "patch()\t ClassNode extenderClass: {0}" , extenderClass);
            
            // search for a no-arg ctor, replace all invokespecial calls in ctors
            for (MethodNode m : (Collection<MethodNode>)theClass.methods) {

                LOG.log(Level.INFO, "patch(101)\t MethodNode m.name: {0}" , m.name);

                if (CONSTRUCTOR_NAME.equals(m.name)) {
                    if (DESC_DEFAULT_CTOR.equals(m.desc)) { // NOI18N
                        LOG.log(Level.INFO, "patch(105)\t DESC_DEFAULT_CTOR: {0}" , DESC_DEFAULT_CTOR);
                        defCtor = m;
                        LOG.log(Level.INFO, "patch(107)\t MethodNode defCtors: {0}" , defCtor);
                    }
                    replaceSuperCtorCalls(theClass, extenderClass, m);
                }
            }

            LOG.log(Level.INFO, "patch(113)\t extenderClass methods.size: {0}" , extenderClass.methods.size());
            //TODO change 'Object o' for MethodNode mn since extenderClass.methods return a List<MethodNode>
            for (Object o : extenderClass.methods) {
                MethodNode mn = (MethodNode)o;
                
                LOG.log(Level.INFO, "patch(118)\t MethodNode mn.name: {0}" ,  mn.name);

                if (mn.invisibleAnnotations != null && (mn.access & Opcodes.ACC_STATIC) > 0) {
                    // constructor, possibly annotated

                    LOG.log(Level.INFO, "patch(123)\t mn.invisibleAnnotations.size(): {0}" ,  mn.invisibleAnnotations.size());
                    LOG.log(Level.INFO, "patch(124)\t mn.access: {0}" ,  mn.access);
                    LOG.log(Level.INFO, "patch(125)\t Opcodes.ACC_STATIC: {0}" ,  Opcodes.ACC_STATIC);
                    //TODO 'mn.invisibleAnnotations' returns List<AnnotationNode>
                    for (AnnotationNode an : (Collection<AnnotationNode>)mn.invisibleAnnotations) {
                        LOG.log(Level.INFO, "patch(128)\t  AnnotationNode an.values: {0}" ,  an.values);
                        LOG.log(Level.INFO, "patch(129)\t  AnnotationNode an.desc: {0}" ,  an.desc);

                        if (DESC_CTOR_ANNOTATION.equals(an.desc)) {

                            delegateToFactory(an, extenderClass, mn, theClass, defCtor);
                            LOG.log(Level.INFO, "patch(134)\t delegateToFactory theClass: {0}" , theClass);
                            break;
                        }
                    }
                }
            }
            LOG.log(Level.INFO, "patch(140)\t theClass.methods.size: {0}" , theClass.methods.size());
            for (MethodNode mn : (Collection<MethodNode>)theClass.methods) {
                if (mn.invisibleAnnotations == null) {
                    continue;
                } //TODO 'mn.invisibleAnnotations' returns List<AnnotationNode>
                for (AnnotationNode an : (Collection<AnnotationNode>)mn.invisibleAnnotations) {
                    if (DESC_PATCHED_PUBLIC_ANNOTATION.equals(an.desc)) {
                        mn.access = (mn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                        break;
                    }
                }
            }
        }
        
        theClass.accept(wr);
        byte[] result = wr.toByteArray();

        LOG.log(Level.INFO, "patch(end)\t result.length: {0}" , result.length);

        return result;
    }

    /**
     * Replaces class references in super constructor invocations.
     * Must not replace references in this() constructor invocations.
     * 
     * @param theClass the class being patched
     * @param extenderClass the injected superclass
     * @param mn method to process
     */
    private static void replaceSuperCtorCalls(final ClassNode theClass, final ClassNode extenderClass, MethodNode mn) {

        LOG.log(Level.INFO, "replaceSuperCtorCalls(start)\t ClassNode extenderClass: {0}" , extenderClass);

        //TODO change Iterator for for-each
        for (Iterator it = mn.instructions.iterator(); it.hasNext(); ) {
            AbstractInsnNode aIns = (AbstractInsnNode)it.next();
            if (aIns.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode mins = (MethodInsnNode)aIns;
                if (CONSTRUCTOR_NAME.equals(mins.name) && mins.owner.equals(extenderClass.superName)) {
                    // replace with the extender class name
                    LOG.log(Level.INFO, "replaceSuperCtorCalls(181)\t theClass.name: {0}" , theClass.name);
                    
                    mins.owner = extenderClass.name;
                    
                    LOG.log(Level.INFO, "replaceSuperCtorCalls(185)\t extenderClass.name: {0}" , extenderClass.name);
                    LOG.log(Level.INFO, "replaceSuperCtorCalls(186)\t mins.owner: {0}" , mins.owner);
                    LOG.log(Level.INFO, "replaceSuperCtorCalls(187)\t theClass.name: {0}" , theClass.name);
                    LOG.log(Level.INFO, "replaceSuperCtorCalls(188)\t theClass: {0}" , theClass);
                }
                break;
            }
        }
        LOG.log(Level.INFO, "replaceSuperCtorCalls(end)\t ClassNode extenderClass: {0}" , extenderClass);
    }
    
    /**
     * No-op singature visitor
     */
    private static class NullSignVisitor extends SignatureVisitor {
        public NullSignVisitor() {
            super(Opcodes.ASM5);
        }
    }
    
    /**
     * Pushes parameters with correct opcodes that correspond to the
     * method's signature. Assumes that the first parameter is the
     * object's class itself.
     */
    private static class CallParametersWriter extends SignatureVisitor {
        private final MethodNode mn;
        private int localSize;
        private int[] paramIndices;
        int [] out = new int[10];
        private int cnt;
        
        /**
         * Adds opcodes to the method's code
         * 
         * @param mn method to generate
         * @param firstSelf if true, assumes the first parameter is reference to self and will generate aload_0
         */
        public CallParametersWriter(MethodNode mn, boolean firstSelf) {
            super(Opcodes.ASM5);
            this.mn = mn;
            this.paramIndex = firstSelf ? 0 : 1;
        }
        
        public CallParametersWriter(MethodNode mn, int[] indices) {
            super(Opcodes.ASM5);
            this.mn = mn;
            this.paramIndices = indices;
        }
        
        private int paramIndex = 0;
        
        void storeLoads() {
            for (int i : paramIndices) {
                mn.visitVarInsn(out[i * 2], out[i * 2 + 1]);
            }
        }
        
        private void load(int opcode, int paramIndex) {
            if (paramIndices == null) {
                mn.visitVarInsn(opcode, paramIndex);
            } else {
                if (out.length <= paramIndex + 1) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[cnt * 2]  = opcode;
                out[cnt * 2 + 1] = paramIndex;
            }
            cnt++;
        }

        @Override
        public void visitEnd() {
            // end of classtype
            load(Opcodes.ALOAD, paramIndex++);
            localSize++;
        }

        @Override
        public void visitBaseType(char c) {
            int idx = paramIndex++;
            int opcode;

            switch (c) {
                // two-word data
                case 'J': opcode = Opcodes.LLOAD; paramIndex++; localSize++; break;
                case 'D': opcode = Opcodes.DLOAD; paramIndex++; localSize++; break;
                // float has a special opcode
                case 'F': opcode = Opcodes.FLOAD; break;
                default: opcode = Opcodes.ILOAD; break;

            }
            load(opcode, idx);
            localSize++;
        }

        @Override
        public SignatureVisitor visitTypeArgument(char c) {
            return new NullSignVisitor();
        }

        @Override
        public void visitTypeArgument() {}

        @Override
        public void visitInnerClassType(String string) {}

        @Override
        public void visitClassType(String string) {}

        @Override
        public SignatureVisitor visitArrayType() {
            load(Opcodes.ALOAD, paramIndex++);
            localSize++;
            return new NullSignVisitor();
        }

        @Override
        public void visitTypeVariable(String string) {}

        @Override
        public SignatureVisitor visitExceptionType() {
            return new NullSignVisitor();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return new NullSignVisitor();
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return this;
        }

        @Override
        public SignatureVisitor visitInterface() {
            return null;
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return null;
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new NullSignVisitor();
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new NullSignVisitor();
        }

        @Override
        public void visitFormalTypeParameter(String string) {
            super.visitFormalTypeParameter(string); //To change body of generated methods, choose Tools | Templates.
        }
    }
    
    private static class CtorDelVisitor extends AnnotationVisitor {
        
        int[] indices;
        int pos;
        int level;
        /**
         * Constructs a new {@link AnnotationVisitor}.
         *
         * @param api the ASM API version implemented by this visitor. Must be one of {@link
         *     Opcodes#ASM4}, {@link Opcodes#ASM5}, {@link Opcodes#ASM6} or {@link Opcodes#ASM7}.
         */
        public CtorDelVisitor(int api) {
            super(api);
        }

        // TODO see BUG https://gitlab.ow2.org/asm/asm/issues/317626
        // AnnotationNode incorrectly process array values in 'visit(final String name, final Object value)' method
        // This does not work from ASM-5.2 to ASM 7.2
        @Override
        public void visit(String string, Object o) {
            if (level > 0) {
                if (pos >= indices.length) {
                    indices = Arrays.copyOf(indices, indices.length * 2);
                }
                indices[pos++] = (Integer)o;
                super.visit(string, o);
                return;
            }
            LOG.log(Level.INFO, "CtorDelVisitor(365)\t string: {0}" , string);
            LOG.log(Level.INFO, "CtorDelVisitor(366)\t Object: {0}" , o);
            if ("delegateParams".equals(string)) {  // NOI18N
                indices = (int[])o;
            }
            super.visit(string, o);
            LOG.log(Level.INFO, "CtorDelVisitor(371)\t indices: {0}" , indices);
        }

        @Override
        public void visitEnd() {
            if (level > 0) {
                if (--level == 0) {
                    if (pos < indices.length) {
                        indices = Arrays.copyOf(indices, pos);
                    }
                }
            }
            super.visitEnd();
        }
        
        

        @Override
        public AnnotationVisitor visitArray(String string) {
            if ("delegateParams".equals(string)) { // NOI18N
                indices = new int[4];
                pos = 0;
                level++;
                return this;
            } else {
                return super.visitArray(string);
            }
        }

    }
    
    private static String[] splitDescriptor(String desc) {
        List<String> arr = new ArrayList<>();
        int lastPos = 0;
        F: for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            switch (c) {
                case '(':
                    lastPos = i+1;
                    break;
                case ')':
                    break F;
                case 'B': case 'C': case 'D': case 'F': case 'I': case 'J':
                case 'S': case 'Z':
                    arr.add(desc.substring(lastPos, i + 1));
                    lastPos = i + 1;
                    break;
                    
                case '[':
                    break;
                    
                case 'L':
                    i = desc.indexOf(';', i);
                    arr.add(desc.substring(lastPos, i + 1));
                    lastPos = i + 1;
                    break;
            }
        }
        return arr.toArray(new String[arr.size()]);
    }
    
    private static void delegateToFactory(
        AnnotationNode an, ClassNode targetClass, MethodNode targetMethod, ClassNode clazz,
        MethodNode noArgCtor
    ) {
        
        LOG.log(Level.INFO, "delegateToFactory(start)\t ClassNode clazz.methods.size(): {0}" , clazz.methods.size());
        LOG.log(Level.INFO, "delegateToFactory(411)\t targetMethod.isNULL: {0}" , (targetMethod == null));
        LOG.log(Level.INFO, "delegateToFactory(412)\t targetClass.isNULL: {0}" , (targetClass == null));
        LOG.log(Level.INFO, "delegateToFactory(413)\t an.isNULL: {0}" , (an == null));
        LOG.log(Level.INFO, "delegateToFactory(414)\t noArgCtor.isNULL: {0}" , (noArgCtor == null));

        String desc = targetMethod.desc;
        CtorDelVisitor v = new CtorDelVisitor(Opcodes.ASM5);
        
        LOG.log(Level.INFO, "delegateToFactory(419)\t CtorDelVisitor v: {0}" , v);
        LOG.log(Level.INFO, "delegateToFactory(420)\t CtorDelVisitor v.indices.isNULL: {0}" , (v.indices == null));

        LOG.log(Level.INFO, "delegateToFactory(422)\t an.desc: {0}" , an.desc);
        LOG.log(Level.INFO, "delegateToFactory(423)\t an.values: {0}" , an.values);
        
        an.accept(v);
        
        LOG.log(Level.INFO, "delegateToFactory(427)\t CtorDelVisitor v: {0}" , v);
        LOG.log(Level.INFO, "delegateToFactory(428)\t CtorDelVisitor v.indices.isNULL: {0}" , (v.indices == null));
        
        int nextPos = desc.indexOf(';', 2); // NOI18N
        desc = "(" + desc.substring(nextPos + 1); // NOI18N

        LOG.log(Level.INFO, "delegateToFactory(433)\t CtorDelVisitor v: {0}" , v);
        LOG.log(Level.INFO, "delegateToFactory(434)\t CtorDelVisitor v.indices.isNULL: {0}" , (v.indices == null));
        LOG.log(Level.INFO, "delegateToFactory(435)\t nextPos: {0}" , nextPos);
        LOG.log(Level.INFO, "delegateToFactory(436)\t Final desc: {0}" , desc);

        MethodNode mn = new MethodNode(Opcodes.ASM5, 
                targetMethod.access & (~Opcodes.ACC_STATIC), CONSTRUCTOR_NAME,
                desc,
                targetMethod.signature,
                (String[])targetMethod.exceptions.toArray(new String[targetMethod.exceptions.size()]));

        LOG.log(Level.INFO, "delegateToFactory(444)\t MethodNode mn: {0}" , mn);
        LOG.log(Level.INFO, "delegateToFactory(445)\t MethodNode mn.name: {0}" , mn.name);

        mn.visibleAnnotations = targetMethod.visibleAnnotations;
        mn.visibleParameterAnnotations = targetMethod.visibleParameterAnnotations;
        mn.parameters = targetMethod.parameters;
        mn.exceptions = targetMethod.exceptions;
        mn.visitCode();
        // this();
        mn.visitVarInsn(Opcodes.ALOAD, 0);

        LOG.log(Level.INFO, "delegateToFactory(455)\t MethodNode mn: {0}" , mn);

        if (v.indices == null) {

            LOG.log(Level.INFO, "delegateToFactory(459)\t Opcodes.INVOKESPECIAL: {0}" , Opcodes.INVOKESPECIAL);
            LOG.log(Level.INFO, "delegateToFactory(460)\t clazz.name: {0}" , clazz.name);
            LOG.log(Level.INFO, "delegateToFactory(461)\t noArgCtor.name: {0}" , noArgCtor.name);
            LOG.log(Level.INFO, "delegateToFactory(462)\t noArgCtor.desc: {0}" , noArgCtor.desc);

            // assume the first parameter is the class:
            mn.visitMethodInsn( Opcodes.INVOKESPECIAL, 
                                clazz.name, 
                                noArgCtor.name, 
                                noArgCtor.desc, 
                                false);
        } else {

            LOG.log(Level.INFO, "delegateToFactory(472)\t CtorDelVisitor v.indices: {0}" , v.indices);

            String[] paramDescs = splitDescriptor(targetMethod.desc);
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i : v.indices) {
                sb.append(paramDescs[i]);
            }
            sb.append(")V");
            SignatureReader r = new SignatureReader(targetMethod.desc);
            CallParametersWriter callWr = new CallParametersWriter(mn, v.indices);
            r.accept(callWr);
            // generate all the parameter loads:
            for (int i : v.indices) {
                mn.visitVarInsn(callWr.out[i * 2], callWr.out[i * 2 + 1]);
            }
            mn.visitMethodInsn(Opcodes.INVOKESPECIAL, 
                    clazz.name, 
                    "<init>", sb.toString(), false);
        }
        // finally call the static method
        // push parameters
        SignatureReader r = new SignatureReader(targetMethod.desc);
        LOG.log(Level.INFO, "delegateToFactory(495)\t SignatureReader r: {0}" , r);

        CallParametersWriter callWr = new CallParametersWriter(mn, true);
        LOG.log(Level.INFO, "delegateToFactory(498)\t callWr: {0}" , callWr);

        r.accept(callWr);
        mn.visitMethodInsn(Opcodes.INVOKESTATIC, targetClass.name, targetMethod.name, targetMethod.desc, false);
        
        mn.visitInsn(Opcodes.RETURN);
        mn.maxStack = callWr.localSize;
        mn.maxLocals = callWr.localSize;
        
        clazz.methods.add(mn);

        LOG.log(Level.INFO, "delegateToFactory(end)\t ClassNode clazz.methods: {0}" , clazz.methods);
    }

    
}
