package org.perlonjava.operators;

import org.perlonjava.ArgumentParser;
import org.perlonjava.Configuration;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class ModuleOperators {
    public static RuntimeScalar doFile(RuntimeScalar runtimeScalar) {
        // `do` file
        String fileName = runtimeScalar.toString();
        Path fullName = null;
        Path filePath = Paths.get(fileName);
        String code = null;

        // If the filename is an absolute path or starts with ./ or ../, use it directly
        if (filePath.isAbsolute() || fileName.startsWith("./") || fileName.startsWith("../")) {
            fullName = Files.exists(filePath) ? filePath : null;
        } else {
            // Otherwise, search in INC directories
            List<RuntimeScalar> inc = GlobalVariable.getGlobalArray("main::INC").elements;
            for (RuntimeBaseEntity dir : inc) {
                String dirName = dir.toString();
                if (dirName.equals(GlobalContext.JAR_PERLLIB)) {
                    // Try to find in jar at "src/main/perl/lib"
                    String resourcePath = "/lib/" + fileName;
                    URL resource = RuntimeScalar.class.getResource(resourcePath);
                    // System.out.println("Found resource " + resource);
                    if (resource != null) {

                        String path = resource.getPath();
                        // Remove leading slash if on Windows
                        if (System.getProperty("os.name").toLowerCase().contains("win") && path.startsWith("/")) {
                            path = path.substring(1);
                        }
                        fullName = Paths.get(path);

                        try (InputStream is = resource.openStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                            StringBuilder content = new StringBuilder();
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                content.append(line).append("\n");
                            }
                            // System.out.println("Content of " + resourcePath + ": " + content.toString());
                            code = content.toString();
                            break;
                        } catch (IOException e1) {
                            //GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
                            //return new RuntimeScalar();
                        }
                    }
                } else {
                    Path fullPath = Paths.get(dirName, fileName);
                    if (Files.exists(fullPath)) {
                        fullName = fullPath;
                        break;
                    }
                }
            }
        }
        if (fullName == null) {
            GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
            return new RuntimeScalar();
        }

        ArgumentParser.CompilerOptions parsedArgs = new ArgumentParser.CompilerOptions();
        parsedArgs.fileName = fullName.toString();
        if (code == null) {
            try {
                code = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
            } catch (IOException e) {
                GlobalVariable.setGlobalVariable("main::!", "Unable to read file " + parsedArgs.fileName);
                return new RuntimeScalar();
            }
        }
        parsedArgs.code = code;

        // set %INC
        getGlobalHash("main::INC").put(fileName, new RuntimeScalar(parsedArgs.fileName));

        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
        } catch (Throwable t) {
            GlobalVariable.setGlobalVariable("main::@", "Error in file " + parsedArgs.fileName +
                    "\n" + t);
            return new RuntimeScalar();
        }

        return result == null ? scalarUndef : result.scalar();
    }

    public static RuntimeScalar require(RuntimeScalar runtimeScalar) {
        // https://perldoc.perl.org/functions/require

        if (runtimeScalar.type == RuntimeScalarType.INTEGER || runtimeScalar.type == RuntimeScalarType.DOUBLE || runtimeScalar.type == RuntimeScalarType.VSTRING || runtimeScalar.type == RuntimeScalarType.BOOLEAN) {
            // `require VERSION`
            Universal.compareVersion(
                    new RuntimeScalar(Configuration.perlVersion),
                    runtimeScalar,
                    "Perl");
            return getScalarInt(1);
        }

        // Look up the file name in %INC
        String fileName = runtimeScalar.toString();
        if (getGlobalHash("main::INC").elements.containsKey(fileName)) {
            // module was already loaded
            return getScalarInt(1);
        }

        // Call `do` operator
        RuntimeScalar result = doFile(runtimeScalar); // `do "fileName"`
        // Check if `do` returned a true value
        if (!result.defined().getBoolean()) {
            // `do FILE` returned undef
            String err = getGlobalVariable("main::@").toString();
            String ioErr = getGlobalVariable("main::!").toString();
            throw new PerlCompilerException(err.isEmpty() ? "Can't locate " + fileName + ": " + ioErr : "Compilation failed in require: " + err);
        }
        return result;
    }
}
