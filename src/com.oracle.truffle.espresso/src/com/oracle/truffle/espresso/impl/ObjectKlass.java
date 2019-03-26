/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.impl;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.espresso.classfile.ConstantValueAttribute;
import com.oracle.truffle.espresso.classfile.EnclosingMethodAttribute;
import com.oracle.truffle.espresso.classfile.InnerClassesAttribute;
import com.oracle.truffle.espresso.classfile.RuntimeConstantPool;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.Attribute;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.runtime.StaticObjectImpl;
import com.oracle.truffle.espresso.substitutions.Host;

/**
 * Resolved non-primitive, non-array types in Espresso.
 */
public final class ObjectKlass extends Klass {

    public static final ObjectKlass[] EMPTY_ARRAY = new ObjectKlass[0];

    private final EnclosingMethodAttribute enclosingMethod;

    private final RuntimeConstantPool pool;

    private final LinkedKlass linkedKlass;

    @CompilationFinal //
    private StaticObject statics;

    @CompilationFinal(dimensions = 1) //
    private Field[] declaredFields;

    @CompilationFinal(dimensions = 1) //
    private Method[] declaredMethods;

    @CompilationFinal int trueDeclaredMethods;

    private final InnerClassesAttribute innerClasses;

    private final Attribute runtimeVisibleAnnotations;

    private final Klass hostKlass;

    @CompilationFinal(dimensions = 1) private final Method[] vtable;
    @CompilationFinal(dimensions = 2) private final Method[][] itable;
    @CompilationFinal(dimensions = 1) private final Klass[] iKlassTable;

    private int initState = LINKED;

    public static final int LOADED = 0;
    public static final int LINKED = 1;
    public static final int PREPARED = 2;
    public static final int INITIALIZED = 3;

    public final Attribute getAttribute(Symbol<Name> name) {
        return linkedKlass.getAttribute(name);
    }

    public ObjectKlass(EspressoContext context, LinkedKlass linkedKlass, ObjectKlass superKlass, ObjectKlass[] superInterfaces, StaticObject classLoader) {
        this(context, linkedKlass, superKlass, superInterfaces, classLoader, null);
    }

    public ObjectKlass(EspressoContext context, LinkedKlass linkedKlass, ObjectKlass superKlass, ObjectKlass[] superInterfaces, StaticObject classLoader, Klass hostKlass) {
        super(context, linkedKlass.getName(), linkedKlass.getType(), superKlass, superInterfaces);

        this.linkedKlass = linkedKlass;
        this.hostKlass = hostKlass;

        this.enclosingMethod = (EnclosingMethodAttribute) getAttribute(EnclosingMethodAttribute.NAME);
        this.innerClasses = (InnerClassesAttribute) getAttribute(InnerClassesAttribute.NAME);

        // Move attribute name to better location.
        this.runtimeVisibleAnnotations = getAttribute(Name.RuntimeVisibleAnnotations);

        // TODO(peterssen): Make writable copy.
        this.pool = new RuntimeConstantPool(getContext(), linkedKlass.getConstantPool(), classLoader);

        LinkedField[] linkedFields = linkedKlass.getLinkedFields();
        Field[] fields = new Field[linkedFields.length];
        for (int i = 0; i < fields.length; ++i) {
            fields[i] = new Field(linkedFields[i], this);
        }
        this.declaredFields = fields;

        LinkedMethod[] linkedMethods = linkedKlass.getLinkedMethods();
        Method[] methods = new Method[linkedMethods.length];
        for (int i = 0; i < methods.length; ++i) {
            methods[i] = new Method(this, linkedMethods[i]);
        }

        this.declaredMethods = methods;
        InterfaceTables.CreationResult methodCR;
        if (this.isInterface()) {
            methodCR = InterfaceTables.create(this, superInterfaces, declaredMethods);
            this.itable = methodCR.getItable();
            this.iKlassTable = methodCR.getiKlass();
            this.vtable = null;
        } else {
            methodCR = InterfaceTables.create(superKlass, superInterfaces, this);
            this.itable = methodCR.getItable();
            this.iKlassTable = methodCR.getiKlass();
            this.vtable = VirtualTable.create(superKlass, declaredMethods, this);
        }
    }

    @Override
    public StaticObject getStatics() {
        if (statics == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            statics = new StaticObjectImpl(this, true);
        }
        return statics;
    }

    @Override
    public boolean isInstanceClass() {
        throw EspressoError.unimplemented();
    }

    @Override
    public int getFlags() {
        return linkedKlass.getFlags();
    }

    @Override
    public boolean isInitialized() {
        return initState == INITIALIZED;
    }

    @Override
    public void initialize() {
        if (!isInitialized()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (getSuperKlass() != null) {
                getSuperKlass().initialize();
            }
            initState = INITIALIZED;

            // TODO(peterssen): Initialize superinterfaces with default methods.

            /**
             * Spec fragment: Then, initialize each final static field of C with the constant value
             * in its ConstantValue attribute (§4.7.2), in the order the fields appear in the
             * ClassFile structure.
             *
             * ...
             *
             * Next, execute the class or interface initialization method of C.
             */
            for (Field f : declaredFields) {
                if (f.isStatic()) {
                    ConstantValueAttribute a = (ConstantValueAttribute) f.getAttribute(Name.ConstantValue);
                    if (a == null) {
                        break;
                    }
                    switch (f.getKind()) {
                        case Boolean: {
                            boolean c = getConstantPool().intAt(a.getConstantvalueIndex()) != 0;
                            f.set(getStatics(), c);
                            break;
                        }
                        case Byte: {
                            byte c = (byte) getConstantPool().intAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Short: {
                            short c = (short) getConstantPool().intAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Char: {
                            char c = (char) getConstantPool().intAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Int: {
                            int c = getConstantPool().intAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Float: {
                            float c = getConstantPool().floatAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Long: {
                            long c = getConstantPool().longAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Double: {
                            double c = getConstantPool().doubleAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        case Object: {
                            StaticObject c = getConstantPool().resolvedStringAt(a.getConstantvalueIndex());
                            f.set(getStatics(), c);
                            break;
                        }
                        default:
                            EspressoError.shouldNotReachHere("invalid constant field kind");
                    }
                }
            }

            Method clinit = getClassInitializer();
            if (clinit != null) {
                clinit.getCallTarget().call();
            }
            assert isInitialized();
        }
    }

    @Override
    public Klass getElementalType() {
        return this;
    }

    @Override
    public @Host(ClassLoader.class) StaticObject getDefiningClassLoader() {
        return pool.getClassLoader();
    }

    @Override
    public RuntimeConstantPool getConstantPool() {
        return pool;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isMember() {
        return false;
    }

    @Override
    public Klass getEnclosingType() {
        return null;
    }

    @Override
    public Method[] getDeclaredConstructors() {
        List<Method> constructors = new ArrayList<>();
        for (Method m : getDeclaredMethods()) {
            if (Name.INIT.equals(m.getName())) {
                constructors.add(m);
            }
        }
        return constructors.toArray(Method.EMPTY_ARRAY);
    }

    @Override
    public Method[] getDeclaredMethods() {
        return declaredMethods;
    }

    @Override
    public Field[] getDeclaredFields() {
        return declaredFields;
    }

    @Override
    public Klass getComponentType() {
        return null;
    }

    public EnclosingMethodAttribute getEnclosingMethod() {
        return enclosingMethod;
    }

    public InnerClassesAttribute getInnerClasses() {
        return innerClasses;
    }

    public final LinkedKlass getLinkedKlass() {
        return linkedKlass;
    }

    public Attribute getRuntimeVisibleAnnotations() {
        return runtimeVisibleAnnotations;
    }

    public int getStaticFieldSlots() {
        return linkedKlass.staticFieldCount;
    }

    public int getInstanceFieldSlots() {
        return linkedKlass.instanceFieldCount;
    }

    @Override
    public Klass getHostClass() {
        return hostKlass;
    }

    Method[] getVTable() {
        return vtable;
    }

    @Override
    public final Method lookupMethod(int index) {
        return (index == -1) ? null : vtable[index];
    }

    @Override
    public final Method lookupMethod(Klass interfKlass, int index) {
        assert (index >= 0) : "Undeclared interface method";
        int i = 0;
        for (Klass k : iKlassTable) {
            if (k == interfKlass) {
                return itable[i][index];
            }
            i++;
        }
        return null;
    }

    final Method[][] getItable() {
        return itable;
    }

    final Klass[] getiKlassTable() {
        return iKlassTable;
    }

    final Method lookupVirtualMethod(Symbol<Name> name, Symbol<Symbol.Signature> signature) {
        for (Method m : vtable) {
            if (m.getName() == name && m.getRawSignature() == signature) {
                return m;
            }
        }
        return null;
    }

    final void setMirandas(ArrayList<InterfaceTables.Miranda> mirandas) {
        this.trueDeclaredMethods = declaredMethods.length;
        Method[] declaredAndMirandaMethods = new Method[declaredMethods.length + mirandas.size()];
        System.arraycopy(declaredMethods, 0, declaredAndMirandaMethods, 0, declaredMethods.length);
        int pos = declaredMethods.length;
        for (InterfaceTables.Miranda miranda : mirandas) {
            declaredAndMirandaMethods[pos++] = miranda.method;
        }
        this.declaredMethods = declaredAndMirandaMethods;
    }

    final Method lookupTrueMethod(Symbol<Name> name, Symbol<Symbol.Signature> signature) {
        ObjectKlass k = this;
        while (k != null) {
            for (int i = 0; i < k.trueDeclaredMethods; i++) {
                Method m = k.declaredMethods[i];
                if (m.getName() == name && m.getRawSignature() == signature) {
                    return m;
                }
            }
            k = k.getSuperKlass();
        }
        return null;
    }
}
