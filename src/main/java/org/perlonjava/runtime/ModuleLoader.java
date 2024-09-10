package org.perlonjava.runtime;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ModuleLoader {

    public static Path findFile(String filename) {
        Path filePath = Paths.get(filename);

        // If the filename is an absolute path or starts with ./ or ../, use it directly
        if (filePath.isAbsolute() || filename.startsWith("./") || filename.startsWith("../")) {
            return Files.exists(filePath) ? filePath : null;
        }

        // Otherwise, search in INC directories

        List<RuntimeBaseEntity> inc = GlobalContext.getGlobalArray("main::INC").elements;

        for (RuntimeBaseEntity dir : inc) {
            Path fullPath = Paths.get(dir.toString(), filename);
            if (Files.exists(fullPath)) {
                return fullPath;
            }
        }

        // File not found
        return null;
    }

}

