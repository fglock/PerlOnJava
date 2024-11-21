Problem statement: It would be easier if I could just to drop a new module, instead of  having to call initialize() in a central place.


Define a Common Interface or Abstract Class: Ensure all modules implement a common interface or extend a common abstract class with an initialize() method.

Use Reflection to Discover Modules: Use Java's reflection capabilities to scan a specific package for classes that implement the interface or extend the abstract class.

Automatically Initialize Modules: Iterate over the discovered classes and invoke their initialize() methods.


```
// src/main/java/org/perlonjava/perlmodule/InitializableModule.java
package org.perlonjava.perlmodule;

public interface InitializableModule {
    void initialize();
}
```


```
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

public class ScalarUtil extends PerlModuleBase implements InitializableModule {

    public ScalarUtil() {
        super("Scalar::Util");
    }

    public static void initialize() {
        // Existing initialization code
    }

    @Override
    public void initialize() {
        ScalarUtil.initialize();
    }
}
```


```
package org.perlonjava.runtime;

import org.perlonjava.perlmodule.InitializableModule;

import java.util.Set;
import org.reflections.Reflections;

public class GlobalContext {

    public static void initializeGlobals(ArgumentParser.CompilerOptions compilerOptions) {
        // Existing initialization code...

        // Use reflection to find and initialize all modules
        Reflections reflections = new Reflections("org.perlonjava.perlmodule");
        Set<Class<? extends InitializableModule>> modules = reflections.getSubTypesOf(InitializableModule.class);

        for (Class<? extends InitializableModule> moduleClass : modules) {
            try {
                InitializableModule module = moduleClass.getDeclaredConstructor().newInstance();
                module.initialize();
            } catch (Exception e) {
                System.err.println("Failed to initialize module: " + moduleClass.getName());
                e.printStackTrace();
            }
        }

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }
}
```


pom.yaml
```
<dependency>
    <groupId>org.reflections</groupId>
    <artifactId>reflections</artifactId>
    <version>0.10.2</version>
</dependency>
```


build.gradle
```
implementation 'org.reflections:reflections:0.10.2'
```


# Alternative using Plain Java JDK


```
// src/main/java/org/perlonjava/perlmodule/InitializableModule.java
package org.perlonjava.perlmodule;

public interface InitializableModule {
    void initialize();
}
```



```
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

public class ScalarUtil extends PerlModuleBase implements InitializableModule {

    public ScalarUtil() {
        super("Scalar::Util");
    }

    @Override
    public void initialize() {
        // Existing initialization code
        ScalarUtil.initialize();
    }
}
```


META-INF/services/org.perlonjava.perlmodule.InitializableModule
```
org.perlonjava.perlmodule.ScalarUtil
// Add other module implementations here
```


```
package org.perlonjava.runtime;

import org.perlonjava.perlmodule.InitializableModule;

import java.util.ServiceLoader;

public class GlobalContext {

    public static void initializeGlobals(ArgumentParser.CompilerOptions compilerOptions) {
        // Existing initialization code...

        // Use ServiceLoader to find and initialize all modules
        ServiceLoader<InitializableModule> serviceLoader = ServiceLoader.load(InitializableModule.class);

        for (InitializableModule module : serviceLoader) {
            try {
                module.initialize();
            } catch (Exception e) {
                System.err.println("Failed to initialize module: " + module.getClass().getName());
                e.printStackTrace();
            }
        }

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }
}
```


