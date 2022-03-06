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

import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.AbstractSourceFileObject;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.JavaSource;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.ClasspathInfo;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.FileObject;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.Snapshot;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.JavacTaskImpl;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nb.CompilationInfo.CacheClearPolicy;
import org.netbeans.api.lexer.TokenHierarchy;
import org.openide.util.Exceptions;

/**
 *
 * @author Tomas Zezula
 */
public final class CompilationInfoImpl {


    private JavaSource.Phase phase = JavaSource.Phase.MODIFIED;
    private CompilationUnitTree compilationUnit;

    private final JavacTaskImpl javacTask;
    private final ClasspathInfo cpInfo;
    private final FileObject file;
    final AbstractSourceFileObject jfo;
    private Snapshot snapshot;
    private final JavacParser parser;
    private final boolean isClassFile;
    private final boolean isDetached;
    JavaSource.Phase parserCrashed = JavaSource.Phase.UP_TO_DATE;      //When javac throws an error, the moveToPhase sets this to the last safe phase
    private final Map<CacheClearPolicy, Map<Object, Object>> userCache = new EnumMap<CacheClearPolicy, Map<Object, Object>>(CacheClearPolicy.class);
    //cache of already parsed files
    private final Map<String, CompilationUnitTree> parsedTrees = new HashMap<>();
    
    public CompilationInfoImpl (final JavacParser parser,
                         final JavaFileObject file,
                         final JavacTaskImpl javacTask,
                         final Snapshot snapshot,
                         final boolean detached) throws IOException {
        assert parser != null;
        this.parser = parser;
        this.cpInfo = parser.getClasspathInfo();
        assert cpInfo != null;
        this.file = new FileObject(file);
        this.snapshot = snapshot;
        assert file == null || snapshot != null;
        this.jfo = new AbstractSourceFileObject(file);
        this.javacTask = javacTask;
        this.isClassFile = false;
        this.isDetached = detached;
    }

    public Snapshot getSnapshot () {
        return this.snapshot;
    }

    /**
     * Returns the current phase of the {@link JavaSource}.
     * @return {@link JavaSource.Phase} the state which was reached by the {@link JavaSource}.
     */
    public JavaSource.Phase getPhase() {
        return this.phase;
    }
    
    /**
     * Returns the javac tree representing the source file.
     * @return {@link CompilationUnitTree} the compilation unit cantaining the top level classes contained in the,
     * java source file. 
     * @throws java.lang.IllegalStateException  when the phase is less than {@link JavaSource.Phase#PARSED}
     */
    public CompilationUnitTree getCompilationUnit() {
        if (this.jfo == null) {
            throw new IllegalStateException ();
        }
        if (this.phase.compareTo (JavaSource.Phase.PARSED) < 0)
            throw new IllegalStateException("Cannot call getCompilationUnit() if current phase < JavaSource.Phase.PARSED. You must call toPhase(Phase.PARSED) first.");//NOI18N
        return this.compilationUnit;
    }
    
    /**
     * Returns the content of the file represented by the {@link JavaSource}.
     * @return String the java source
     */
    public String getText() {
        if (!hasSource()) {
            throw new IllegalStateException ();
        }
        try {
            return this.jfo.getCharContent(false).toString();
        } catch (IOException ioe) {
            //Should never happen
            Exceptions.printStackTrace(ioe);
            return null;
        }
    }
    
    /**
     * Returns the {@link TokenHierarchy} for the file represented by the {@link JavaSource}.
     * @return lexer TokenHierarchy
     */
    public TokenHierarchy<?> getTokenHierarchy() {
        if (!hasSource()) {
            throw new IllegalStateException ();
        }
        try {
            return this.jfo.getTokenHierarchy();
        } catch (IOException ioe) {
            //Should never happen
            Exceptions.printStackTrace(ioe);
            return null;
        }
    }

    /**
     * Returns {@link ClasspathInfo} for which this {@link CompilationInfoImpl} was created.
     * @return ClasspathInfo
     */
    public ClasspathInfo getClasspathInfo() {
	return this.cpInfo;
    }
    
    /**
     * Returns {@link JavacParser} which created this {@link CompilationInfoImpl}
     * or null when the {@link CompilationInfoImpl} was created for no files.
     * @return {@link JavacParser} or null
     */
    public JavacParser getParser () {
        return this.parser;
    }
    
    
    /**
     * Returns the {@link FileObject} represented by this {@link CompilationInfo}.
     * @return FileObject
     */
    public FileObject getFileObject () {
        return this.file;
    }
    
    public boolean isClassFile () {
        return this.isClassFile;
    }
    
    public Map<String, CompilationUnitTree> getParsedTrees() {
        return this.parsedTrees;
    }

                                
    /**
     * Moves the state to required phase. If given state was already reached 
     * the state is not changed. The method will throw exception if a state is 
     * illegal required. Acceptable parameters for thid method are <BR>
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.PARSED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.ELEMENTS_RESOLVED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.RESOLVED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.UP_TO_DATE}   
     * @param phase The required phase
     * @return the reached state
     * @throws IllegalArgumentException in case that given state can not be 
     *         reached using this method
     * @throws IOException when the file cannot be red
     */    
    public JavaSource.Phase toPhase(JavaSource.Phase phase ) throws IOException {
        return toPhase(phase, Collections.emptyList());
    }

    /**
     * Moves the state to required phase. If given state was already reached 
     * the state is not changed. The method will throw exception if a state is 
     * illegal required. Acceptable parameters for thid method are <BR>
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.PARSED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.ELEMENTS_RESOLVED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.RESOLVED}
     * <LI>{@link org.netbeans.api.java.source.JavaSource.Phase.UP_TO_DATE}   
     * @param phase The required phase
     * @return the reached state
     * @throws IllegalArgumentException in case that given state can not be 
     *         reached using this method
     * @throws IOException when the file cannot be red
     */    
    public JavaSource.Phase toPhase(JavaSource.Phase phase, List<FileObject> forcedSources ) throws IOException {
        if (phase == JavaSource.Phase.MODIFIED) {
            throw new IllegalArgumentException( "Invalid phase: " + phase );    //NOI18N
        }
        if (!hasSource()) {
            JavaSource.Phase currentPhase = getPhase();
            if (currentPhase.compareTo(phase)<0) {
                setPhase(phase);
                if (currentPhase == JavaSource.Phase.MODIFIED)
                    getJavacTask().getElements().getTypeElement("java.lang.Object"); // Ensure proper javac initialization
            }
            return phase;
        } else {
            JavaSource.Phase currentPhase = parser.moveToPhase(phase, this, forcedSources, false);
            return currentPhase.compareTo (phase) < 0 ? currentPhase : phase;
        }
    }

    /**
     * Returns {@link JavacTaskImpl}, when it doesn't exist
     * it's created.
     * @return JavacTaskImpl
     */
    public synchronized JavacTaskImpl getJavacTask() {
        return getJavacTask(Collections.emptyList());
    }

    /**
     * Returns {@link JavacTaskImpl}, when it doesn't exist
     * it's created.
     * @return JavacTaskImpl
     */
    public synchronized JavacTaskImpl getJavacTask(List<FileObject> forcedSources) {
        assert javacTask != null;
	return javacTask;
    }

    List<FileObject> getForcedSources() {
        return Collections.emptyList();
    }

    public Object getCachedValue(Object key) {
        for (Map<Object, Object> c : userCache.values()) {
            Object res = c.get(key);

            if (res != null) return res;
        }

        return null;
    }

    public void putCachedValue(Object key, Object value, CacheClearPolicy clearPolicy) {
        for (Map<Object, Object> c : userCache.values()) {
            c.remove(key);
        }

        Map<Object, Object> c = userCache.get(clearPolicy);

        if (c == null) {
            userCache.put(clearPolicy, c = new HashMap<Object, Object>());
        }

        c.put(key, value);
    }

    public void taskFinished() {
        userCache.remove(CacheClearPolicy.ON_TASK_END);
    }

    /**
     * Sets the current {@link JavaSource.Phase}
     * @param phase
     */
    void setPhase(final JavaSource.Phase phase) {
        assert phase != null;
        this.phase = phase;
    }
    
    /**
     * Sets the {@link CompilationUnitTree}
     * @param compilationUnit
     */
    void setCompilationUnit(final CompilationUnitTree compilationUnit) {
        assert compilationUnit != null;
        this.compilationUnit = compilationUnit;
    }

    private boolean hasSource() {
        return this.jfo != null && !isClassFile;
    }

    List<JavaFileObject> parsedFiles;
    void setParsedFiles(List<JavaFileObject> parsedFiles) {
        this.parsedFiles = parsedFiles;
    }

    List<JavaFileObject> getParsedFiles() {
        return parsedFiles;
    }
}
