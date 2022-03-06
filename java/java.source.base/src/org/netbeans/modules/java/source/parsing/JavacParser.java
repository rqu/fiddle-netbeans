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

import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.JavaSource;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.ClasspathInfo;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.FileObject;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.Snapshot;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Abort;

import org.netbeans.lib.nbjavac.services.CancelAbort;

import com.sun.tools.javac.util.Context;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.JavaFileObject;
import com.oracle.graalvm.fiddle.compiler.nbjavac.nb.PostFlowAnalysis;

import com.oracle.graalvm.fiddle.compiler.nbjavac.nbstubs.JavaSource.Phase;

/**
 * Provides Parsing API parser built atop Javac (using JSR 199).
 * @author Tomas Zezula
 */
//@NotThreadSafe
public class JavacParser {
    public static final String OPTION_PATCH_MODULE = "--patch-module";          //NOI18N
    public static final String NB_X_MODULE = "-Xnb-Xmodule:";                   //NOI18N
    //Timer logger
    private static final Logger TIME_LOGGER = Logger.getLogger("TIMER");        //NOI18N
    //Debug logger
    private static final Logger LOGGER = Logger.getLogger(JavacParser.class.getName());
    //Java Mime Type
    public static final String MIME_TYPE = "text/x-java";
    public static final String LOMBOK_DETECTED = "lombokDetected";

    /**
     * Helper map mapping the {@link Phase} to message for performance logger
     */
    private static final Map<Phase, String> phase2Message = new EnumMap<> (Phase.class);

    static {
        phase2Message.put (Phase.PARSED,"Parsed");                              //NOI18N
        phase2Message.put (Phase.ELEMENTS_RESOLVED,"Signatures Attributed");    //NOI18N
        phase2Message.put (Phase.RESOLVED, "Attributed");                       //NOI18N
    }

    //Cancelling of parser
    private final AtomicBoolean parserCanceled = new AtomicBoolean();

    //ClassPaths used by the parser
    private ClasspathInfo cpInfo;
    //all the files for which parser was created for
    private final Collection<Snapshot> snapshots;
    private final SequentialParsing sequentialParsing = null;

    public JavacParser(Collection<Snapshot> snapshots, ClasspathInfo cpInfo) {
        this.snapshots = snapshots;
        this.cpInfo = cpInfo;
    }

    private void invalidate (final boolean reinit) {
    }

    /**
     * Returns {@link ClasspathInfo} used by this javac
     * @return the ClasspathInfo
     */
    ClasspathInfo getClasspathInfo () {
        return this.cpInfo;
    }

    /**
     * Moves the Javac into the required {@link JavaSource#Phase}
     * Not synchronized, has to be called under Parsing API lock.
     * @param the required {@link JavaSource#Phase}
     * @parma currentInfo - the javac
     * @param cancellable when true the method checks cancels
     * @return the reached phase
     * @throws IOException when the javac throws an exception
     */
    Phase moveToPhase (final Phase phase, final CompilationInfoImpl currentInfo, List<FileObject> forcedSources,
            final boolean cancellable) throws IOException {
        JavaSource.Phase parserError = currentInfo.parserCrashed;
        assert parserError != null;
        Phase currentPhase = currentInfo.getPhase();
        try {
            if (currentPhase.compareTo(Phase.PARSED) < 0 && phase.compareTo(Phase.PARSED) >= 0 && phase.compareTo(parserError) <= 0) {
                if (cancellable && parserCanceled.get()) {
                    //Keep the currentPhase unchanged, it may happen that an userActionTask
                    //runnig after the phace completion task may still use it.
                    return Phase.MODIFIED;
                }
                long start = System.currentTimeMillis();
                Iterable<? extends CompilationUnitTree> trees = null;
                Iterator<? extends CompilationUnitTree> it = null;
                CompilationUnitTree unit = null;
                if (snapshots.size() > 1 && currentInfo.getParsedTrees() != null && currentInfo.getParsedTrees().containsKey(currentInfo.jfo.getName())) {
                    unit = currentInfo.getParsedTrees().get(currentInfo.jfo.getName());
                } else {

                    if (sequentialParsing != null) {
                        trees = sequentialParsing.parse(currentInfo.getJavacTask(), currentInfo.jfo);
                    } else {
                        trees = currentInfo.getJavacTask(forcedSources).parse();
                    }
                    if (unit == null) {
                        if (trees == null) {
                            LOGGER.log(Level.INFO, "Did not parse anything for: {0}", currentInfo.jfo.toUri()); //NOI18N
                            return Phase.MODIFIED;
                        }
                        it = trees.iterator();
                        if (!it.hasNext()) {
                            LOGGER.log(Level.INFO, "Did not parse anything for: {0}", currentInfo.jfo.toUri()); //NOI18N
                            return Phase.MODIFIED;
                        }

                        List<JavaFileObject> parsedFiles = new ArrayList<>();
                        while (it.hasNext()) {
                            CompilationUnitTree oneFileTree = it.next();
                            parsedFiles.add(oneFileTree.getSourceFile());
                            CompilationUnitTree put = currentInfo.getParsedTrees().put(oneFileTree.getSourceFile().getName(), oneFileTree);
                        }
                        currentInfo.setParsedFiles(parsedFiles);
                        unit = trees.iterator().next();
                    }

                }

                currentInfo.setCompilationUnit(unit);

                currentPhase = Phase.PARSED;
                long end = System.currentTimeMillis();
                FileObject currentFile = currentInfo.getFileObject();
                TIME_LOGGER.log(Level.FINE, "Compilation Unit",
                    new Object[] {currentFile, unit});

                logTime (currentFile,currentPhase,(end-start));
            }
            if (currentPhase == Phase.PARSED && phase.compareTo(Phase.ELEMENTS_RESOLVED)>=0 && phase.compareTo(parserError)<=0) {
                if (cancellable && parserCanceled.get()) {
                    return Phase.MODIFIED;
                }
                long start = System.currentTimeMillis();
                Supplier<Object> setJavacHandler = () -> null;
                Consumer<Object> restoreHandler = h -> {};
                try {
                    //the DeferredCompletionFailureHandler should be set to javac mode:
                    Class<?> dcfhClass = Class.forName("com.sun.tools.javac.code.DeferredCompletionFailureHandler");
                    Class<?> dcfhHandlerClass = Class.forName("com.sun.tools.javac.code.DeferredCompletionFailureHandler$Handler");
                    Object dcfh = dcfhClass.getDeclaredMethod("instance", Context.class).invoke(null, currentInfo.getJavacTask().getContext());
                    Method setHandler = dcfhClass.getDeclaredMethod("setHandler", dcfhHandlerClass);
                    Object javacCodeHandler = dcfhClass.getDeclaredField("javacCodeHandler").get(dcfh);

                    setJavacHandler = () -> {
                        try {
                            return setHandler.invoke(dcfh, javacCodeHandler);
                        } catch (ReflectiveOperationException ex) {
                            LOGGER.log(Level.FINE, null, ex);
                            return null;
                        }
                    };
                    restoreHandler = h -> {
                        if (h != null) {
                            try {
                                setHandler.invoke(dcfh, h);
                            } catch (ReflectiveOperationException ex) {
                                LOGGER.log(Level.WARNING, null, ex);
                            }
                        }
                    };
                } catch (ReflectiveOperationException | SecurityException ex) {
                    //ignore
                    LOGGER.log(Level.FINEST, null, ex);
                }
                Object oldHandler = setJavacHandler.get();
                try {
                    currentInfo.getJavacTask().enter();
                } finally {
                    restoreHandler.accept(oldHandler);
                }
                currentPhase = Phase.ELEMENTS_RESOLVED;
                long end = System.currentTimeMillis();
                logTime(currentInfo.getFileObject(),currentPhase,(end-start));
           }
           if (currentPhase == Phase.ELEMENTS_RESOLVED && phase.compareTo(Phase.RESOLVED)>=0 && phase.compareTo(parserError)<=0) {
                if (cancellable && parserCanceled.get()) {
                    return Phase.MODIFIED;
                }
                long start = System.currentTimeMillis ();
                JavacTaskImpl jti = currentInfo.getJavacTask();
                JavaCompiler compiler = JavaCompiler.instance(jti.getContext());
                List<Env<AttrContext>> savedTodo = new ArrayList<>(compiler.todo);
                try {
                    compiler.todo.retainFiles(currentInfo.getParsedFiles());
                    savedTodo.removeAll(compiler.todo);
                    PostFlowAnalysis.analyze(jti.analyze(), jti.getContext());
                } finally {
                    for (Env<AttrContext> env : savedTodo) {
                        compiler.todo.offer(env);
                    }
                }
                currentPhase = Phase.RESOLVED;
                long end = System.currentTimeMillis ();
                logTime(currentInfo.getFileObject(),currentPhase,(end-start));
            }
            if (currentPhase == Phase.RESOLVED && phase.compareTo(Phase.UP_TO_DATE)>=0) {
                currentPhase = Phase.UP_TO_DATE;
            }
        } catch (CancelAbort ca) {
            currentPhase = Phase.MODIFIED;
            invalidate(false);
        } catch (Abort abort) {
            parserError = currentPhase;
        } catch (RuntimeException | Error ex) {
            if (cancellable && parserCanceled.get()) {
                currentPhase = Phase.MODIFIED;
                invalidate(false);
            } else {
                parserError = currentPhase;
                dumpSource(currentInfo, ex);
                throw ex;
            }
        } finally {
            currentInfo.setPhase(currentPhase);
            currentInfo.parserCrashed = parserError;
        }
        return currentPhase;
    }

    public static void logTime (FileObject source, Phase phase, long time) {
    }

    public static void dumpSource(CompilationInfoImpl info, Throwable exc) {
    }

    public static interface SequentialParsing {
        public Iterable<? extends CompilationUnitTree> parse(JavacTask task, JavaFileObject file) throws IOException;
    }
}
