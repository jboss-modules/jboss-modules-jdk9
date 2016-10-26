/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.jboss.modules;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.security.AccessController.doPrivileged;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JDK-specific classes which are replaced for different JDK major versions.  This one is for Java 9 only.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class JDKSpecific {

    // === private fields and data ===

    static final Set<String> MODULES_PACKAGES = new HashSet<>(Arrays.asList(
        "org/jboss/modules",
        "org/jboss/modules/filter",
        "org/jboss/modules/log",
        "org/jboss/modules/management",
        "org/jboss/modules/ref"
    ));

    static final StackWalker STACK_WALKER = doPrivileged((PrivilegedAction<StackWalker>) () -> StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE));

    // === the actual JDK-specific API ===

    static Class<?> getCallingUserClass() {
        return STACK_WALKER.walk(stream -> stream.skip(1).findFirst().get().getDeclaringClass());
    }

    static Class<?> getCallingClass() {
        return STACK_WALKER.walk(JDKSpecific::processFrame);
    }

    static boolean isParallelCapable(ConcurrentClassLoader cl) {
        // TODO this API isn't merged yet
        // return cl.isParallelCapable();
        return ConcurrentClassLoader.getLockForClass(cl, "$TEST$") != cl;
    }

    static Package getPackage(ClassLoader cl, String packageName) {
        return cl.getDefinedPackage(packageName);
    }

    static Enumeration<URL> getPlatformResources(String name) throws IOException {
        final int index = name.lastIndexOf('/');
        if (index > 0 && MODULES_PACKAGES.contains(name.substring(0, index))) {
            return JDKSpecific.class.getClassLoader().getResources(name);
        } else {
            return ClassLoader.getPlatformClassLoader().getResources(name);
        }
    }

    static Set<String> getJDKPaths() {
        Set<String> pathSet = new FastCopyHashSet<>(1024);
        processRuntimeImages(pathSet);
        // TODO: Remove this stuff once jboss-modules is itself a module
        final String javaClassPath = AccessController.doPrivileged(new PropertyReadAction("java.class.path"));
        JDKPaths.processClassPathItem(javaClassPath, new FastCopyHashSet<>(1024), pathSet);
        pathSet.addAll(MODULES_PACKAGES);
        return pathSet;
    }

    // === nested util stuff, non-API ===

    private static Class<?> processFrame(Stream<StackWalker.StackFrame> stream) {
        final Iterator<StackWalker.StackFrame> iterator = stream.iterator();
        if (! iterator.hasNext()) return null;
        iterator.next();
        if (! iterator.hasNext()) return null;
        iterator.next();
        if (! iterator.hasNext()) return null;
        Class<?> testClass = iterator.next().getDeclaringClass();
        while (iterator.hasNext()) {
            final Class<?> item = iterator.next().getDeclaringClass();
            if (testClass != item) {
                return item;
            }
        }
        return null;
    }

    private static void processRuntimeImages(final Set<String> jarSet) {
        try {
            for (final Path root : FileSystems.getFileSystem(new URI("jrt:/")).getRootDirectories()) {
                Files.walkFileTree(root, new JrtFileVisitor(jarSet));
            }
        } catch (final URISyntaxException |IOException e) {
            throw new IllegalStateException("Unable to process java runtime images");
        }
    }

    private static class JrtFileVisitor implements FileVisitor<Path> {

        private static final String SLASH = "/";
        private static final String PACKAGES = "/packages";
        private final Set<String> pathSet;

        private JrtFileVisitor(final Set<String> pathSet) {
            this.pathSet = pathSet;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
            final String d = dir.toString();
            return d.equals(SLASH) || d.startsWith(PACKAGES) ? CONTINUE : SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            String f = file.toString();
            pathSet.add(f.substring(PACKAGES.length() + 1, f.lastIndexOf(SLASH)).replace('.', '/'));
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
            return CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
            return CONTINUE;
        }
    }
}
