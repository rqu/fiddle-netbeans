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

import com.oracle.graalvm.fiddle.compiler.nbjavac.ClassIndex;
import com.oracle.graalvm.fiddle.compiler.nbjavac.NotImplemented;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.util.Context;

import javax.tools.JavaFileManager;
import javax.tools.StandardLocation;

import org.netbeans.api.annotations.common.NonNull;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.JavaSource.Phase;

/**
 *
 * @author Dusan Balek
 */
public class SourceUtils {

    public static boolean checkTypesAssignable(CompilationInfo info, TypeMirror from, TypeMirror to) {
        Context c = ((JavacTaskImpl) info.impl.getJavacTask()).getContext();
        if (from.getKind() == TypeKind.TYPEVAR) {
            Types types = Types.instance(c);
            TypeVar t = types.substBound((TypeVar)from, com.sun.tools.javac.util.List.of((Type)from), com.sun.tools.javac.util.List.of(types.boxedTypeOrType((Type)to)));
            return info.getTypes().isAssignable(t.getUpperBound(), to)
                    || info.getTypes().isAssignable(to, t.getUpperBound());
        }
        if (from.getKind() == TypeKind.WILDCARD) {
            from = Types.instance(c).wildUpperBound((Type)from);
        }
        return Check.instance(c).checkType(null, (Type)from, (Type)to).getKind() != TypeKind.ERROR;
    }
    
    public static TypeMirror getBound(WildcardType wildcardType) {
        Type.TypeVar bound = ((Type.WildcardType)wildcardType).bound;
            return bound != null ? bound.getUpperBound() : null;
        }

    /**
     * Returns a list of completions for an annotation attribute value suggested by
     * annotation processors.
     * 
     * @param info the CompilationInfo used to resolve annotation processors
     * @param element the element being annotated
     * @param annotation the (perhaps partial) annotation being applied to the element
     * @param member the annotation member to return possible completions for
     * @param userText source code text to be completed
     * @return suggested completions to the annotation member
     * 
     * @since 0.57
     */
    public static List<? extends Completion> getAttributeValueCompletions(CompilationInfo info, Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
        List<Completion> completions = new LinkedList<>();
        if (info.getPhase().compareTo(Phase.ELEMENTS_RESOLVED) >= 0) {
            String fqn = ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
            Iterable<? extends Processor> processors = NotImplemented.todo("SourceUtils.getAttributeValueCompletions processors");
//                    JavacParser.ProcessorHolder.instance(info.impl.getJavacTask().getContext()).getProcessors();
            if (processors != null) {
                for (Processor processor : processors) {
                    boolean match = false;
                    for (String sat : processor.getSupportedAnnotationTypes()) {
                        if ("*".equals(sat)) { //NOI18N
                            match = true;
                            break;
                        } else if (sat.endsWith(".*")) { //NOI18N
                            sat = sat.substring(0, sat.length() - 1);
                            if (fqn.startsWith(sat)) {
                                match = true;
                                break;
                            }
                        } else if (fqn.equals(sat)) {
                            match = true;
                            break;
                        }
                    }
                    if (match) {
                        try {
                            for (Completion c : processor.getCompletions(element, annotation, member, userText)) {
                                completions.add(c);
                            }
                        } catch (Exception e) {
                            Logger.getLogger(processor.getClass().getName()).log(Level.INFO, e.getMessage(), e);
                        }
                    }
                }
            }
        }
        return completions;
    }

    /**
     * Resolves all captured type variables to their respective wildcards in the given type.
     * @param info CompilationInfo over which the method should work
     * @param tm type to resolve
     * @return resolved type
     * 
     * @since 0.136
     */
    public static TypeMirror resolveCapturedType(CompilationInfo info, TypeMirror tm) {
        TypeMirror type = resolveCapturedTypeInt(info, tm);
        
        if (type.getKind() == TypeKind.WILDCARD) {
            TypeMirror tmirr = ((WildcardType) type).getExtendsBound();
            tmirr = tmirr != null ? tmirr : ((WildcardType) type).getSuperBound();
            if (tmirr != null) {
                return tmirr;
            } else { //no extends, just '?
                TypeElement tel = info.getElements().getTypeElement("java.lang.Object"); // NOI18N
                return tel == null ? null : tel.asType();
            }
                
        }
        
        return type;
    }
    
    private static TypeMirror resolveCapturedTypeInt(CompilationInfo info, TypeMirror tm) {
        if (tm == null) {
            return tm;
        }
        
        TypeMirror orig = resolveCapturedType(tm);

        if (orig != null) {
            tm = orig;
        }
        
        if (tm.getKind() == TypeKind.WILDCARD) {
            TypeMirror extendsBound = ((WildcardType) tm).getExtendsBound();
            TypeMirror rct = resolveCapturedTypeInt(info, extendsBound != null ? extendsBound : ((WildcardType) tm).getSuperBound());
            if (rct != null) {
                return rct.getKind() == TypeKind.WILDCARD ? rct : info.getTypes().getWildcardType(extendsBound != null ? rct : null, extendsBound == null ? rct : null);
            }
        }
        
        if (tm.getKind() == TypeKind.DECLARED) {
            DeclaredType dt = (DeclaredType) tm;
            TypeElement el = (TypeElement) dt.asElement();
            if (((DeclaredType)el.asType()).getTypeArguments().size() != dt.getTypeArguments().size()) {
                return info.getTypes().getDeclaredType(el);
            }
            
            List<TypeMirror> typeArguments = new LinkedList<>();
            
            for (TypeMirror t : dt.getTypeArguments()) {
                typeArguments.add(resolveCapturedTypeInt(info, t));
            }
            
            final TypeMirror enclosingType = dt.getEnclosingType();
            if (enclosingType.getKind() == TypeKind.DECLARED) {
                return info.getTypes().getDeclaredType((DeclaredType) enclosingType, el, typeArguments.toArray(new TypeMirror[0]));
            } else {
                return info.getTypes().getDeclaredType(el, typeArguments.toArray(new TypeMirror[0]));
            }
        }

        if (tm.getKind() == TypeKind.ARRAY) {
            ArrayType at = (ArrayType) tm;
            TypeMirror componentType = resolveCapturedTypeInt(info, at.getComponentType());
            switch (componentType.getKind()) {
                case VOID:
                case EXECUTABLE:
                case WILDCARD:  // heh!
                case PACKAGE:
                    break;
                default:
                    return info.getTypes().getArrayType(componentType);
            }
        }
        
        return tm;
    }
    /**
     * @since 0.24
     */
    public static WildcardType resolveCapturedType(TypeMirror type) {
        if (type instanceof Type.CapturedType) {
            return ((Type.CapturedType) type).wildcard;
        } else {
            return null;
        }
    }
    
    /**
     * Returns all elements of the given scope that are declared after given position in a source.
     * @param path to the given search scope
     * @param pos position in the source
     * @param sourcePositions
     * @param trees
     * @return collection of forward references
     * 
     * @since 0.136
     */
    public static Collection<? extends Element> getForwardReferences(TreePath path, int pos, SourcePositions sourcePositions, Trees trees) {
        HashSet<Element> refs = new HashSet<>();
        Element el;
        
        while(path != null) {
            switch(path.getLeaf().getKind()) {
                case VARIABLE:
                    el = trees.getElement(path);
                    if (el != null) {
                        refs.add(el);
                    }
                    TreePath parent = path.getParentPath();
                    if (TreeUtilities.CLASS_TREE_KINDS.contains(parent.getLeaf().getKind())) {
                        boolean isStatic = ((VariableTree)path.getLeaf()).getModifiers().getFlags().contains(Modifier.STATIC);
                        for(Tree member : ((ClassTree)parent.getLeaf()).getMembers()) {
                            if (member.getKind() == Tree.Kind.VARIABLE && sourcePositions.getStartPosition(path.getCompilationUnit(), member) >= pos &&
                                    (isStatic || !((VariableTree)member).getModifiers().getFlags().contains(Modifier.STATIC))) {
                                el = trees.getElement(new TreePath(parent, member));
                                if (el != null) {
                                    refs.add(el);
                                }
                            }
                        }
                    }
                    break;
                case ENHANCED_FOR_LOOP:
                    EnhancedForLoopTree efl = (EnhancedForLoopTree)path.getLeaf();
                    if (sourcePositions.getEndPosition(path.getCompilationUnit(), efl.getExpression()) >= pos) {
                        el = trees.getElement(new TreePath(path, efl.getVariable()));
                        if (el != null) {
                            refs.add(el);
                        }
                    }                        
            }
            path = path.getParentPath();
        }
        return refs;
    }
    
    /**
     * Returns names of all modules within given scope.
     * @param info the CompilationInfo used to resolve modules
     * @param scope to search in {@see SearchScope}
     * @return set of module names
     * @since 2.23
     */
    public static Set<String> getModuleNames(CompilationInfo info, final @NonNull Set<? extends ClassIndex.SearchScopeType> scope) {
        Set<String> ret = new HashSet<>();
        JavaFileManager jfm = info.impl.getJavacTask().getContext().get(JavaFileManager.class);
        if (jfm != null) {
            List<JavaFileManager.Location> toSearch = new ArrayList<>();
            for (ClassIndex.SearchScopeType s : scope) {
                if (s.isSources()) {
                    toSearch.add(StandardLocation.MODULE_SOURCE_PATH);
                }
                if (s.isDependencies()) {
                    toSearch.add(StandardLocation.MODULE_PATH);
                    toSearch.add(StandardLocation.UPGRADE_MODULE_PATH);
                    toSearch.add(StandardLocation.SYSTEM_MODULES);
                }
            }
            try {
                for (JavaFileManager.Location searchLocation : toSearch) {
                    for (Set<JavaFileManager.Location> locations : jfm.listLocationsForModules(searchLocation)) {
                        for (JavaFileManager.Location location : locations) {
                            ret.add(jfm.inferModuleName(location));
                        }
                    }
                }
            } catch (IOException ioe) {}
        }
        return ret;
    }
}
