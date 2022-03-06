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

package com.oracle.graalvm.fiddle.compiler.nbjavac.nb;

import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.ModuleFinder;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.jvm.Target;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Name;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nb.ElementUtils;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nb.TreeShims;

import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.openide.util.Parameters;
import org.openide.util.WeakSet;

/**
 * Represents a handle for {@link Element} which can be kept and later resolved
 * by another javac. The javac {@link Element}s are valid only in a single
 * {@link javax.tools.CompilationTask} or a single run of a
 * {@link CancellableTask}. A client needing to
 * keep a reference to an {@link Element} and use it in another {@link CancellableTask}
 * must serialize it into an {@link ElementHandle}.
 * Currently not all {@link Element}s can be serialized. See {@link #create} for details.
 * <div class="nonnormative">
 * <p>
 * Typical usage of {@link ElementHandle} is as follows:
 * </p>
 * <pre>
 * final ElementHandle[] elementHandle = new ElementHandle[1];
 * javaSource.runUserActionTask(new Task&lt;CompilationController>() {
 *     public void run(CompilationController compilationController) {
 *         compilationController.toPhase(Phase.RESOLVED);
 *         CompilationUnitTree cu = compilationController.getTree();
 *         List&lt;? extends Tree> types = getTypeDecls(cu);
 *         Tree tree = getInterestingElementTree(types);
 *         Element element = compilationController.getElement(tree);
 *         elementHandle[0] = ElementHandle.create(element);
 *    }
 * }, true);
 *
 * otherJavaSource.runUserActionTask(new Task&lt;CompilationController>() {
 *     public void run(CompilationController compilationController) {
 *         compilationController.toPhase(Phase.RESOLVED);
 *         Element element = elementHandle[0].resolve(compilationController);
 *         // ....
 *    }
 * }, true);
 * </pre>
 * </div>
 * @author Tomas Zezula
 */
public final class ElementHandle<T extends Element> {
    private static final Logger log = Logger.getLogger(ElementHandle.class.getName());
    
    private final ElementKind kind;
    private final String[] signatures;
        
       
    private ElementHandle(final ElementKind kind, String... signatures) {
        assert kind != null;
        assert signatures != null;
        this.kind = kind;
        this.signatures = signatures;
    }
    
    
    /**
     * Resolves an {@link Element} from the {@link ElementHandle}.
     * @param compilationInfo representing the {@link javax.tools.CompilationTask}
     * in which the {@link Element} should be resolved.
     * @return resolved subclass of {@link Element} or null if the elment does not exist on
     * the classpath/sourcepath of {@link javax.tools.CompilationTask}.
     */
    @SuppressWarnings ("unchecked")     // NOI18N
    public @CheckForNull T resolve (@NonNull final CompilationInfo compilationInfo) {
        Parameters.notNull("compilationInfo", compilationInfo); // NOI18N
        ModuleElement module;

        if (compilationInfo.getFileObject() != null) {
            JCTree.JCCompilationUnit cut = (JCTree.JCCompilationUnit)compilationInfo.getCompilationUnit();
            if (cut != null) {
                module = cut.modle;
            } else if (compilationInfo.getTopLevelElements().iterator().hasNext()) {
                module = ((Symbol) compilationInfo.getTopLevelElements().iterator().next()).packge().modle;
            } else {
                module = null;
            }
        } else {
            module = null;
        }
        T result = resolveImpl (module, compilationInfo.impl.getJavacTask());
        if (result == null) {
            if (log.isLoggable(Level.INFO))
                log.log(Level.INFO, "Cannot resolve: {0}", toString()); //NOI18N                
        } else {
            if (log.isLoggable(Level.FINE))
                log.log(Level.FINE, "Resolved element = {0}", result);
        }
        return result;
    }
        
    
    private T resolveImpl (final ModuleElement module, final JavacTaskImpl jt) {
        if (log.isLoggable(Level.FINE))
            log.log(Level.FINE, "Resolving element kind: {0}", this.kind); // NOI18N       
        ElementKind simplifiedKind = this.kind;
        if (simplifiedKind.name().equals("RECORD")) {
            simplifiedKind = ElementKind.CLASS; //TODO: test
        }
        if (simplifiedKind.name().equals("RECORD_COMPONENT")) {
            simplifiedKind = ElementKind.FIELD; //TODO: test
        }
        switch (simplifiedKind) {
            case PACKAGE:
                assert signatures.length == 1;
                return (T) jt.getElements().getPackageElement(signatures[0]);
            case CLASS:
            case INTERFACE:
            case ENUM:
            case ANNOTATION_TYPE: {
                assert signatures.length == 1;
                final Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                if (type instanceof TypeElement) {
                    return (T) type;
                } else  {
                    log.log(Level.INFO, "Resolved type is null for kind = {0}", this.kind);  // NOI18N
                }
                break;
            }
            case OTHER:
                assert signatures.length == 1;
                return (T) getTypeElementByBinaryName (module, signatures[0], jt);
            case METHOD:
            case CONSTRUCTOR:            
            {
                assert signatures.length == 3;
                final Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                if (type instanceof TypeElement) {
                   final List<? extends Element> members = type.getEnclosedElements();
                   for (Element member : members) {
                       if (this.kind == member.getKind()) {
                           String[] desc = ClassFileUtil.createExecutableDescriptor((ExecutableElement)member);
                           assert desc.length == 3;
                           if (this.signatures[1].equals(desc[1]) && this.signatures[2].equals(desc[2])) {
                               return (T) member;
                           }
                       }
                   }
                } else if (type != null) {
                    return (T) new Symbol.MethodSymbol(0, (Name) jt.getElements().getName(this.signatures[1]), Symtab.instance(jt.getContext()).unknownType, (Symbol)type);
                } else 
                    log.log(Level.INFO, "Resolved type is null for kind = {0}", this.kind);  // NOI18N
                break;
            }
            case INSTANCE_INIT:
            case STATIC_INIT:
            {
                assert signatures.length == 2;
                final Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                if (type instanceof TypeElement) {
                   final List<? extends Element> members = type.getEnclosedElements();
                   for (Element member : members) {
                       if (this.kind == member.getKind()) {
                           String[] desc = ClassFileUtil.createExecutableDescriptor((ExecutableElement)member);
                           assert desc.length == 2;
                           if (this.signatures[1].equals(desc[1])) {
                               return (T) member;
                           }
                       }
                   }
                } else
                    log.log(Level.INFO, "Resolved type is null for kind = {0}", this.kind); // NOI18N
                break;
            }
            case FIELD:
            case ENUM_CONSTANT:
            {
                assert signatures.length == 3;
                final Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                if (type instanceof TypeElement) {
                    final List<? extends Element> members = type.getEnclosedElements();
                    for (Element member : members) {
                        if (this.kind == member.getKind()) {
                            String[] desc = ClassFileUtil.createFieldDescriptor((VariableElement)member);
                            assert desc.length == 3;
                            if (this.signatures[1].equals(desc[1]) && this.signatures[2].equals(desc[2])) {
                                return (T) member;
                            }
                        }
                    }
                } else if (type != null) {
                    return (T) new Symbol.VarSymbol(0, (Name) jt.getElements().getName(this.signatures[1]), Symtab.instance(jt.getContext()).unknownType, (Symbol)type);
                } else 
                    log.log(Level.INFO, "Resolved type is null for kind = {0}", this.kind); // NOI18N
                break;
            }
            case TYPE_PARAMETER:
            {
                if (signatures.length == 2) {
                     Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                     if (type instanceof TypeElement) {
                         List<? extends TypeParameterElement> tpes = ((TypeElement)type).getTypeParameters();
                         for (TypeParameterElement tpe : tpes) {
                             if (tpe.getSimpleName().contentEquals(signatures[1])) {
                                 return (T)tpe;
                             }
                         }
                     } else 
                        log.log(Level.INFO, "Resolved type is null for kind = {0} signatures.length = {1}", new Object[] {this.kind, signatures.length});   // NOI18N
                }
                else if (signatures.length == 4) {
                    final Element type = getTypeElementByBinaryName (module, signatures[0], jt);
                    if (type instanceof TypeElement) {
                        final List<? extends Element> members = type.getEnclosedElements();
                        for (Element member : members) {
                            if (member.getKind() == ElementKind.METHOD || member.getKind() == ElementKind.CONSTRUCTOR) {
                                String[] desc = ClassFileUtil.createExecutableDescriptor((ExecutableElement)member);
                                assert desc.length == 3;
                                if (this.signatures[1].equals(desc[1]) && this.signatures[2].equals(desc[2])) {
                                    assert member instanceof ExecutableElement;
                                    List<? extends TypeParameterElement> tpes =((ExecutableElement)member).getTypeParameters();
                                    for (TypeParameterElement tpe : tpes) {
                                        if (tpe.getSimpleName().contentEquals(signatures[3])) {
                                            return (T) tpe;
                                        }
                                    }
                                }
                            }
                        }
                    } else 
                        log.log(Level.INFO, "Resolved type is null for kind = {0} signatures.length = {1}", new Object[] {this.kind, signatures.length}); // NOI18N
                }
                else {
                    throw new IllegalStateException ();
                }
                break;
            }
            case MODULE:
                assert signatures.length == 1;
                final ModuleFinder cml = ModuleFinder.instance(jt.getContext());
                final Element me = cml.findModule((Name)jt.getElements().getName(this.signatures[0]));
                if (me != null) {
                    return (T) me;
                } else {
                    log.log(Level.INFO, "Cannot resolve module: {0}", this.signatures[0]);  // NOI18N
                }
                break;
            default:
                throw new IllegalStateException ();
        }
        if (log.isLoggable(Level.FINE))
            log.log(Level.FINE, "All resolvings failed. Returning null.");  // NOI18N
        return null;
    }
    
    /**
     * Returns a qualified name of the {@link TypeElement} represented by this
     * {@link ElementHandle}. When the {@link ElementHandle} doesn't represent
     * a {@link TypeElement} it throws a {@link IllegalStateException}
     * @return the qualified name
     * @throws an {@link IllegalStateException} when this {@link ElementHandle} 
     * isn't creatred for the {@link TypeElement}.
     */
    public @NonNull String getQualifiedName () throws IllegalStateException {
        if ((this.kind.isClass() && !isArray(signatures[0])) ||
                this.kind.isInterface() ||
                this.kind == ElementKind.MODULE ||
                this.kind == ElementKind.OTHER) {
            return this.signatures[0].replace (Target.DEFAULT.syntheticNameChar(),'.');    //NOI18N
        }
        else {
            throw new IllegalStateException ();
        }
    }
    
    /**
     * Returns the {@link ElementKind} of this element handle,
     * it is the kind of the {@link Element} from which the handle
     * was created.
     * @return {@link ElementKind}
     *
     */
    public @NonNull ElementKind getKind () {
        return this.kind;
    }
    
    private static final WeakSet<ElementHandle<?>> NORMALIZATION_CACHE = new WeakSet<ElementHandle<?>>();

    /**
     * Factory method for creating {@link ElementHandle}.
     * @param element for which the {@link ElementHandle} should be created. Permitted
     * {@link ElementKind}s
     * are: {@link ElementKind#PACKAGE}, {@link ElementKind#CLASS},
     * {@link ElementKind#INTERFACE}, {@link ElementKind#ENUM}, {@link ElementKind#ANNOTATION_TYPE}, {@link ElementKind#METHOD},
     * {@link ElementKind#CONSTRUCTOR}, {@link ElementKind#INSTANCE_INIT}, {@link ElementKind#STATIC_INIT},
     * {@link ElementKind#FIELD}, and {@link ElementKind#ENUM_CONSTANT}.
     * @return a new {@link ElementHandle}
     * @throws IllegalArgumentException if the element is of an unsupported {@link ElementKind}
     */
    public static @NonNull <T extends Element> ElementHandle<T> create (@NonNull final T element) throws IllegalArgumentException {
        ElementHandle<T> eh = createImpl(element);

        return (ElementHandle<T>) NORMALIZATION_CACHE.putIfAbsent(eh);
    }
    
    private static @NonNull <T extends Element> ElementHandle<T> createImpl (@NonNull final T element) throws IllegalArgumentException {
        Parameters.notNull("element", element);
        ElementKind kind = element.getKind();
        ElementKind simplifiedKind = kind;
        if (TreeShims.isRecord(element)) {
            simplifiedKind = ElementKind.CLASS;
        }
        if (TreeShims.isRecordComponent(element)) {
            simplifiedKind = ElementKind.FIELD;
        }
        String[] signatures;
        switch (simplifiedKind) {
            case PACKAGE:
                assert element instanceof PackageElement;
                signatures = new String[]{((PackageElement)element).getQualifiedName().toString()};
                break;
            case CLASS:
            case INTERFACE:
            case ENUM:
            case ANNOTATION_TYPE:
                assert element instanceof TypeElement;
                signatures = new String[] {ClassFileUtil.encodeClassNameOrArray((TypeElement)element)};
                break;
            case METHOD:
            case CONSTRUCTOR:                
            case INSTANCE_INIT:
            case STATIC_INIT:
                assert element instanceof ExecutableElement;
                signatures = ClassFileUtil.createExecutableDescriptor((ExecutableElement)element);
                break;
            case FIELD:
            case ENUM_CONSTANT:
                assert element instanceof VariableElement;
                signatures = ClassFileUtil.createFieldDescriptor((VariableElement)element);
                break;
            case TYPE_PARAMETER:
                assert element instanceof TypeParameterElement;
                TypeParameterElement tpe = (TypeParameterElement) element;
                Element ge = tpe.getGenericElement();
                ElementKind gek = ge.getKind();
                if (gek.isClass() || gek.isInterface()) {
                    assert ge instanceof TypeElement;
                    signatures = new String[2];
                    signatures[0] = ClassFileUtil.encodeClassNameOrArray((TypeElement)ge);
                    signatures[1] = tpe.getSimpleName().toString();
                }
                else if (gek == ElementKind.METHOD || gek == ElementKind.CONSTRUCTOR) {
                    assert ge instanceof ExecutableElement;
                    String[] _sigs = ClassFileUtil.createExecutableDescriptor((ExecutableElement)ge);
                    signatures = new String[_sigs.length + 1];
                    System.arraycopy(_sigs, 0, signatures, 0, _sigs.length);
                    signatures[_sigs.length] = tpe.getSimpleName().toString();
                }
                else {
                    throw new IllegalArgumentException(gek.toString());
                }
                break;
            case MODULE:
                signatures = new String[]{((ModuleElement)element).getQualifiedName().toString()};
                break;
            default:
                throw new IllegalArgumentException(kind.toString());
        }
        return new ElementHandle<T> (kind, signatures);
    }
    
    private static Element getTypeElementByBinaryName (final ModuleElement module, final String signature, final JavacTaskImpl jt) {
        if (log.isLoggable(Level.FINE))
            log.log(Level.FINE, "Calling getTypeElementByBinaryName: signature = {0}", signature);
        if (isNone(signature)) {
            return Symtab.instance(jt.getContext()).noSymbol;
        }
        else if (isArray(signature)) {
            return Symtab.instance(jt.getContext()).arrayClass;
        }
        else {
            return (TypeElement) (module != null
                    ? ElementUtils.getTypeElementByBinaryName(jt, module, signature)
                    : ElementUtils.getTypeElementByBinaryName(jt, signature));
        }
    }
    
    private static boolean isNone (String signature) {
        return signature.length() == 0;
    }

    private static boolean isArray (String signature) {
        return signature.length() == 1 && signature.charAt(0) == '[';
    }
}
