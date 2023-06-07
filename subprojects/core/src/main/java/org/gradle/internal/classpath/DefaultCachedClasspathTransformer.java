/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.internal.classpath;

import com.google.common.collect.ImmutableList;
import org.gradle.api.NonNullApi;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory;
import org.gradle.internal.Either;
import org.gradle.internal.agents.AgentStatus;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.FileAccessTracker;
import org.gradle.internal.file.FileException;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.vfs.FileSystemAccess;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.empty;
import static org.gradle.internal.Either.left;
import static org.gradle.internal.Either.right;
import static org.gradle.internal.UncheckedException.unchecked;
import static org.gradle.internal.classpath.TypeHierarchyRegistry.TypeHierarchyReaderWriter.readFromFile;
import static org.gradle.internal.classpath.TypeHierarchyRegistry.TypeHierarchyReaderWriter.writeToFile;

public class DefaultCachedClasspathTransformer implements CachedClasspathTransformer, Closeable {

    private final PersistentCache cache;
    private final FileAccessTracker fileAccessTracker;
    private final ClasspathWalker classpathWalker;
    private final ClasspathBuilder classpathBuilder;
    private final FileSystemAccess fileSystemAccess;
    private final GlobalCacheLocations globalCacheLocations;
    private final FileLockManager fileLockManager;
    private final AgentStatus agentStatus;
    private final ManagedExecutor executor;
    private final ParallelTransformExecutor parallelTransformExecutor;
    private final TypeRegistryProvider typeRegistryProvider;

    public DefaultCachedClasspathTransformer(
        GlobalScopedCacheBuilderFactory cacheBuilderFactory,
        ClasspathTransformerCacheFactory classpathTransformerCacheFactory,
        FileAccessTimeJournal fileAccessTimeJournal,
        ClasspathWalker classpathWalker,
        ClasspathBuilder classpathBuilder,
        FileSystemAccess fileSystemAccess,
        ExecutorFactory executorFactory,
        GlobalCacheLocations globalCacheLocations,
        FileLockManager fileLockManager,
        AgentStatus agentStatus
    ) {
        this.classpathWalker = classpathWalker;
        this.classpathBuilder = classpathBuilder;
        this.fileSystemAccess = fileSystemAccess;
        this.globalCacheLocations = globalCacheLocations;
        this.fileLockManager = fileLockManager;
        this.agentStatus = agentStatus;
        this.cache = classpathTransformerCacheFactory.createCache(cacheBuilderFactory, fileAccessTimeJournal);
        this.fileAccessTracker = classpathTransformerCacheFactory.createFileAccessTracker(cache, fileAccessTimeJournal);
        this.executor = executorFactory.create("jar transforms", Runtime.getRuntime().availableProcessors());
        this.parallelTransformExecutor = new ParallelTransformExecutor(cache, executor);
        this.typeRegistryProvider = new TypeRegistryProvider(cache, parallelTransformExecutor, classpathWalker, this::snapshotOf);
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(executor, cache).stop();
    }

    @Override
    public ClassPath transform(ClassPath classPath, StandardTransform transform) {
        if (classPath.isEmpty()) {
            return classPath;
        }
        return transformPipelineFor(transform).transform(classPath);

    }

    @FunctionalInterface
    private interface TransformPipeline {
        ClassPath transform(ClassPath original);
    }

    private TransformPipeline transformPipelineFor(StandardTransform transform) {
        switch (transform) {
            case None:
                return copyingPipeline();
            case BuildLogic:
                if (!agentStatus.isAgentInstrumentationEnabled()) {
                    return instrumentingPipeline(InstrumentingClasspathFileTransformer.instrumentForLoadingWithClassLoader());
                }
                return agentInstrumentingPipeline(copyingPipeline(), instrumentingPipeline(InstrumentingClasspathFileTransformer.instrumentForLoadingWithAgent()));
            default:
                throw new IllegalArgumentException();
        }
    }

    private TransformPipeline copyingPipeline() {
        return cp -> transformFiles(cp, new CopyingClasspathFileTransformer(globalCacheLocations));
    }

    private TransformPipeline instrumentingPipeline(InstrumentingClasspathFileTransformer.Policy policy) {
        return cp -> transformFiles(cp, instrumentingClasspathFileTransformerFor(policy, new InstrumentingTransformer()));
    }

    private TransformPipeline agentInstrumentingPipeline(TransformPipeline originalsPipeline, TransformPipeline transformedPipeline) {
        return classPath -> {
            ClassPath copiedClassPath = originalsPipeline.transform(classPath);
            ClassPath transformedClassPath = transformedPipeline.transform(classPath);
            List<File> copiedOriginalJars = copiedClassPath.getAsFiles();
            List<File> transformedJars = transformedClassPath.getAsFiles();
            int size = copiedOriginalJars.size();
            assert size == transformedJars.size();
            TransformedClassPath.Builder result = TransformedClassPath.builderWithExactSize(size);
            for (int i = 0; i < size; ++i) {
                result.add(copiedOriginalJars.get(i), transformedJars.get(i));
            }
            return result.build();
        };
    }

    @Override
    public ClassPath transform(ClassPath classPath, StandardTransform transform, Transform additional) {
        if (classPath.isEmpty()) {
            return classPath;
        }
        return transformFiles(
            classPath,
            instrumentingClasspathFileTransformerFor(
                InstrumentingClasspathFileTransformer.instrumentForLoadingWithClassLoader(),
                new CompositeTransformer(additional, transformerFor(transform))
            )
        );
    }

    @Override
    public List<URL> transform(Collection<URL> urls, StandardTransform transform) {
        if (urls.isEmpty()) {
            return ImmutableList.of();
        }
        ClasspathFileTransformer transformer = fileTransformerFor(transform);
        TypeHierarchyRegistry typeRegistry = transformer instanceof InstrumentingClasspathFileTransformer
            ? typeRegistryProvider.getFor(urls, transformer.getConfigHash())
            : new TypeHierarchyRegistry();
        return parallelTransformExecutor.transformAll(
            urls,
            (url, seen) -> cachedURL(url, transformer, seen, typeRegistry)
        );
    }

    private ClassPath transformFiles(ClassPath classPath, ClasspathFileTransformer transformer) {
        TypeHierarchyRegistry typeRegistry = transformer instanceof InstrumentingClasspathFileTransformer
            ? typeRegistryProvider.getFor(classPath.getAsFiles(), transformer.getConfigHash())
            : new TypeHierarchyRegistry();
        return DefaultClassPath.of(
            parallelTransformExecutor.transformAll(
                classPath.getAsFiles(),
                (file, seen) -> cachedFile(file, transformer, seen, typeRegistry)
            )
        );
    }

    private Transform transformerFor(StandardTransform transform) {
        if (transform == StandardTransform.BuildLogic) {
            return new InstrumentingTransformer();
        } else {
            throw new UnsupportedOperationException("Not implemented yet.");
        }
    }

    private ClasspathFileTransformer fileTransformerFor(StandardTransform transform) {
        switch (transform) {
            case BuildLogic:
                return instrumentingClasspathFileTransformerFor(InstrumentingClasspathFileTransformer.instrumentForLoadingWithClassLoader(), new InstrumentingTransformer());
            case None:
                return new CopyingClasspathFileTransformer(globalCacheLocations);
            default:
                throw new IllegalArgumentException();
        }
    }

    private InstrumentingClasspathFileTransformer instrumentingClasspathFileTransformerFor(InstrumentingClasspathFileTransformer.Policy policy, Transform transform) {
        return new InstrumentingClasspathFileTransformer(fileLockManager, classpathWalker, classpathBuilder, policy, transform);
    }

    private Optional<Either<URL, Callable<URL>>> cachedURL(URL original, ClasspathFileTransformer transformer, Set<HashCode> seen, TypeHierarchyRegistry typeRegistry) {
        if (original.getProtocol().equals("file")) {
            return cachedFile(Convert.urlToFile(original), transformer, seen, typeRegistry).map(
                result -> result.fold(
                    file -> left(Convert.fileToURL(file)),
                    transform -> right(() -> Convert.fileToURL(transform.call()))
                )
            );
        }
        return Optional.of(left(original));
    }

    private Optional<Either<File, Callable<File>>> cachedFile(
        File original,
        ClasspathFileTransformer transformer,
        Set<HashCode> seen,
        TypeHierarchyRegistry typeRegistry
    ) {
        FileSystemLocationSnapshot snapshot = snapshotOf(original);
        if (snapshot.getType() == FileType.Missing) {
            return empty();
        }
        if (shouldUseFromCache(original)) {
            final HashCode contentHash = snapshot.getHash();
            if (!seen.add(contentHash)) {
                // Already seen an entry with the same content hash, ignore it
                return empty();
            }
            // It's a new content hash, transform it
            return Optional.of(
                right(() -> transformFile(original, snapshot, transformer, typeRegistry))
            );
        }
        return Optional.of(left(original));
    }

    private File transformFile(File original, FileSystemLocationSnapshot snapshot, ClasspathFileTransformer transformer, TypeHierarchyRegistry typeRegistry) {
        final File result = transformer.transform(original, snapshot, cache.getBaseDir(), typeRegistry);
        markAccessed(result, original);
        return result;
    }

    private FileSystemLocationSnapshot snapshotOf(File file) {
        return fileSystemAccess.read(file.getAbsolutePath());
    }

    private boolean shouldUseFromCache(File original) {
        // Transform everything that has not already been transformed
        return !original.toPath().startsWith(cache.getBaseDir().toPath());
    }

    private void markAccessed(File result, File original) {
        if (!result.equals(original)) {
            fileAccessTracker.markAccessed(result);
        }
    }

    @FunctionalInterface
    private interface ValueOrTransformProvider<T, U> {
        Optional<Either<U, Callable<U>>> apply(T input, Set<HashCode> seen);
    }

    @NonNullApi
    private static class ParallelTransformExecutor {

        private final PersistentCache cache;
        private final ManagedExecutor executor;

        private ParallelTransformExecutor(PersistentCache cache, ManagedExecutor executor) {
            this.cache = cache;
            this.executor = executor;
        }

        public <T, U> List<U> transformAll(Collection<T> inputs, ValueOrTransformProvider<T, U> valueOrTransformProvider) {
            assert !inputs.isEmpty();
            return cache.useCache(() -> {

                final List<U> results = new ArrayList<>(inputs.size());
                final List<Callable<Void>> transforms = new ArrayList<>(inputs.size());
                final Set<HashCode> seen = new HashSet<>();
                for (T input : inputs) {
                    valueOrTransformProvider.apply(input, seen).ifPresent(valueOrTransform ->
                        valueOrTransform.apply(
                            value -> results.add(value),
                            transform -> {
                                final int index = results.size();
                                results.add(null);
                                transforms.add(() -> {
                                    results.set(index, unchecked(transform));
                                    return null;
                                });
                            }
                        )
                    );
                }

                // Execute all transforms at once
                for (Future<Void> result : unchecked(() -> executor.invokeAll(transforms))) {
                    // Propagate first failure
                    unchecked(result::get);
                }

                return results;
            });
        }
    }

    @NonNullApi
    private static class TypeRegistryProvider {

        private static final Logger LOGGER = Logging.getLogger(TypeRegistryProvider.class);

        private final PersistentCache cache;
        private final ParallelTransformExecutor parallelExecutor;
        private final ClasspathWalker classpathWalker;
        private final Function<File, FileSystemLocationSnapshot> snapshotFun;

        public TypeRegistryProvider(PersistentCache cache, ParallelTransformExecutor parallelExecutor, ClasspathWalker classpathWalker,
                                    Function<File, FileSystemLocationSnapshot> snapshotFun) {
            this.cache = cache;
            this.parallelExecutor = parallelExecutor;
            this.classpathWalker = classpathWalker;
            this.snapshotFun = snapshotFun;
        }

        public TypeHierarchyRegistry getFor(Collection<URL> urls, HashCode configHash) {
            List<File> files = urls.stream()
                .filter(url -> url.getProtocol().equals("file"))
                .map(Convert::urlToFile)
                .collect(Collectors.toList());
            return getFor(files, configHash);
        }

        public TypeHierarchyRegistry getFor(List<File> files, HashCode configHash) {
            List<TypeHierarchyRegistry> typeRegistries = parallelExecutor.transformAll(files, (File source, Set<HashCode> seen) -> visitClassHierarchyForFile(source, seen, configHash));
            return TypeHierarchyRegistry.of(typeRegistries);
        }

        private Optional<Either<TypeHierarchyRegistry, Callable<TypeHierarchyRegistry>>> visitClassHierarchyForFile(File source, Set<HashCode> seen, HashCode configHash) {
            FileSystemLocationSnapshot snapshot = snapshotFun.apply(source.getAbsoluteFile());
            if (snapshot.getType() == FileType.Missing || !seen.add(snapshot.getHash())) {
                // Don't visit missing files or files that have already been visited
                return Optional.empty();
            }
            return Optional.of(Either.right(() -> visitClassHierarchyForFile(source, snapshot, configHash)));
        }

        private TypeHierarchyRegistry visitClassHierarchyForFile(File source, FileSystemLocationSnapshot sourceSnapshot, HashCode configHash) throws IOException {
            TypeHierarchyRegistry typeRegistry = new TypeHierarchyRegistry();
            InstrumentingClasspathFileTransformer.Policy policy = InstrumentingClasspathFileTransformer.instrumentForLoadingWithClassLoader();
            String destDirName = hashOf(sourceSnapshot, configHash);
            File destDir = new File(cache.getBaseDir(), destDirName);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            String destFileName = sourceSnapshot.getType() == FileType.Directory ? source.getName() + ".jar" : source.getName();
            File hierachyFile = new File(destDir, destFileName + ".hierarchy");
            if (hierachyFile.isFile()) {
                return readFromFile(hierachyFile);
            }

            try {
                classpathWalker.visit(source, entry -> {
                    if (!policy.includeEntry(entry)) {
                        return;
                    }
                    if (entry.getName().endsWith(".class")) {
                        typeRegistry.visit(entry.getContent());
                    }
                });
            } catch (FileException e) {
                // Badly formed archive, so discard the contents and produce an empty registry
                LOGGER.debug("Malformed archive '{}'. No type hierarchy to discover.", source.getName(), e);
            }
            writeToFile(typeRegistry, hierachyFile);
            return typeRegistry;
        }

        // TODO: Reuse this somehow from InstrumentingClasspathFileTransformer
        private static String hashOf(FileSystemLocationSnapshot sourceSnapshot, HashCode configHash) {
            Hasher hasher = Hashing.defaultFunction().newHasher();
            hasher.putHash(configHash);
            // TODO - apply runtime classpath normalization?
            hasher.putHash(sourceSnapshot.getHash());
            return hasher.hash().toString();
        }
    }

    private static class Convert {

        public static File urlToFile(URL original) {
            return new File(unchecked(original::toURI));
        }

        public static URL fileToURL(File file) {
            return unchecked(file.toURI()::toURL);
        }
    }
}
