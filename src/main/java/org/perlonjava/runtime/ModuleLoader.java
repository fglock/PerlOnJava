package org.perlonjava.runtime;


import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.net.URL;

public class ModuleLoader {

    public static String moduleToFilename(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new IllegalArgumentException("Module name cannot be null or empty");
        }

        // Replace '::' with '/' and append '.pm'
        return moduleName.replace("::", "/") + ".pm";
    }

    public static Path findFile(String filename) {
        Path filePath = Paths.get(filename);

        // If the filename is an absolute path or starts with ./ or ../, use it directly
        if (filePath.isAbsolute() || filename.startsWith("./") || filename.startsWith("../")) {
            return Files.exists(filePath) ? filePath : null;
        }

        // Otherwise, search in INC directories

        List<RuntimeScalar> inc = GlobalContext.getGlobalArray("main::INC").elements;

        for (RuntimeBaseEntity dir : inc) {
            Path fullPath = Paths.get(dir.toString(), filename);
            if (Files.exists(fullPath)) {
                return fullPath;
            }
        }

        // If not found in file system, try to find in jar
        // at "src/main/perl/lib"
        String resourcePath = "/lib/" + filename;
        URL resource = ModuleLoader.class.getResource(resourcePath);
        if (resource != null) {
            try {
                if (resource.getProtocol().equals("jar")) {
                    // Handle JAR resources
                    try (FileSystem fileSystem = FileSystems.newFileSystem(resource.toURI(), Collections.emptyMap())) {
                        return fileSystem.getPath(resourcePath);
                    }
                } else {
                    // Handle file system resources
                    return Paths.get(resource.toURI());
                }
            } catch (URISyntaxException | IOException e) {
                throw new IllegalArgumentException("Invalid resource path '" + resource + "': " + e.getMessage());
            }
        }

        // File not found
        return null;
    }

}

