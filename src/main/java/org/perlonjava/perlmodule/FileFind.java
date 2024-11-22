package org.perlonjava.perlmodule;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The FileFind class provides functionality to traverse directory trees
 * and perform operations on each file or directory found. It is similar
 * to Perl's File::Find module, allowing for depth-first traversal with
 * various options for handling symbolic links and directory processing.
 */
public class FileFind {

    /**
     * Options class encapsulates the configuration for directory traversal.
     * It includes options for handling symbolic links, directory processing,
     * and callback functions for file operations.
     */
    public static class Options {
        /**
         * The wanted function to be executed on each file or directory.
         */
        public Consumer<Path> wanted;

        /**
         * If true, perform a post-order traversal (process directories after their contents).
         */
        public boolean bydepth = false;

        /**
         * Function to preprocess directory contents before traversal.
         */
        public Function<List<Path>, List<Path>> preprocess;

        /**
         * Function to execute after processing a directory.
         */
        public Consumer<Path> postprocess;

        /**
         * If true, follow symbolic links during traversal.
         */
        public boolean follow = false;

        /**
         * If true, follow symbolic links quickly, allowing duplicates.
         */
        public boolean followFast = false;

        /**
         * Determines behavior when a file is visited more than once:
         * 0 - Throw an exception.
         * 1 - Skip the subtree (default).
         * 2 - Continue without processing duplicates.
         */
        public int followSkip = 1;

        /**
         * Function to handle dangling symbolic links.
         */
        public Consumer<Path> danglingSymlinks;

        /**
         * If true, do not change directories during traversal.
         */
        public boolean noChdir = false;

        /**
         * Constructs an Options object with the mandatory wanted function.
         *
         * @param wanted The function to execute on each file or directory.
         */
        public Options(Consumer<Path> wanted) {
            this.wanted = wanted;
        }
    }

    /**
     * Performs a depth-first traversal of the specified directories,
     * applying the options and executing the wanted function on each file or directory.
     *
     * @param options     The options for traversal, including the wanted function.
     * @param directories The list of directories to traverse.
     */
    public static void find(Options options, List<String> directories) {
        for (String dir : directories) {
            traverse(new File(dir).toPath(), options, new HashSet<>());
        }
    }

    /**
     * Performs a depth-first traversal of the specified directories in post-order,
     * applying the options and executing the wanted function on each file or directory.
     *
     * @param options     The options for traversal, including the wanted function.
     * @param directories The list of directories to traverse.
     */
    public static void finddepth(Options options, List<String> directories) {
        options.bydepth = true;
        find(options, directories);
    }

    /**
     * Recursively traverses the directory tree starting from the given path,
     * applying the specified options and executing the wanted function.
     *
     * @param path    The starting path for traversal.
     * @param options The options for traversal, including the wanted function.
     * @param visited A set of visited paths to handle symbolic links and avoid cycles.
     */
    private static void traverse(Path path, Options options, Set<Path> visited) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (options.follow || options.followFast) {
                        if (Files.isSymbolicLink(dir)) {
                            Path realPath = dir.toRealPath();
                            if (!visited.add(realPath)) {
                                return handleFollowSkip(options, dir);
                            }
                        }
                    }
                    if (options.preprocess != null) {
                        List<Path> children = new ArrayList<>();
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                            stream.forEach(children::add);
                        }
                        children = options.preprocess.apply(children);
                        for (Path child : children) {
                            traverse(child, options, visited);
                        }
                    }
                    if (!options.bydepth && options.wanted != null) {
                        options.wanted.accept(dir);
                    }
                    return options.noChdir ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (options.follow || options.followFast) {
                        if (Files.isSymbolicLink(file)) {
                            Path realPath = file.toRealPath();
                            if (!visited.add(realPath)) {
                                return handleFollowSkip(options, file);
                            }
                        }
                    }
                    if (options.wanted != null) {
                        options.wanted.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    if (Files.isSymbolicLink(file) && options.danglingSymlinks != null) {
                        options.danglingSymlinks.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (options.bydepth && options.wanted != null) {
                        options.wanted.accept(dir);
                    }
                    if (options.postprocess != null) {
                        options.postprocess.accept(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error traversing path: " + path);
        }
    }

    /**
     * Handles the behavior when a file is visited more than once based on the followSkip option.
     *
     * @param options The options for traversal.
     * @param path    The path that was visited more than once.
     * @return The FileVisitResult indicating the action to take.
     */
    private static FileVisitResult handleFollowSkip(Options options, Path path) {
        switch (options.followSkip) {
            case 0:
                throw new IllegalStateException("File visited more than once: " + path);
            case 1:
                return FileVisitResult.SKIP_SUBTREE;
            case 2:
                return FileVisitResult.CONTINUE;
            default:
                return FileVisitResult.CONTINUE;
        }
    }
}
