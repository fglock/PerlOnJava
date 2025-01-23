# Modular Design for PerlOnJava Extensions

This document outlines the approach for creating modular extensions containing both Perl (.pm) and Java (.java) components.

## General Approach

### Module Structure
```
perlonjava-module-name/
  src/
    main/
      java/
        org/perlonjava/module/
          ModuleProvider.java
          ModuleImplementation.java
      perl/
        lib/
          Module.pm
  pom.xml
  build.gradle
```

### Key Components

1. Module Provider Interface
```java
package org.perlonjava.module;

public interface ModuleProvider {
    void initialize();
    String getModuleName(); 
}
```

2. ServiceLoader Configuration
```
META-INF/services/org.perlonjava.module.ModuleProvider
```

### Build System Integration

Maven parent pom.xml:
```xml
<project>
    <groupId>org.perlonjava</groupId>
    <artifactId>perlonjava-modules</artifactId>
    <packaging>pom</packaging>
    
    <modules>
        <module>perlonjava-core</module>
        <module>perlonjava-module-dbi</module>
    </modules>
</project>
```

Gradle settings.gradle:
```gradle
include ':perlonjava-core'
include ':perlonjava-module-dbi'
```

## DBI Module Example

### Implementation

1. Module Provider:
```java
package org.perlonjava.module.dbi;

public class DbiModuleProvider implements ModuleProvider {
    @Override
    public void initialize() {
        Dbi.initialize();
    }
    
    @Override 
    public String getModuleName() {
        return "DBI";
    }
}
```

2. Runtime Integration:
```java
// In StatementParser.parseUseDeclaration()
if (packageName.equals("DBI")) {
    ServiceLoader<ModuleProvider> providers = ServiceLoader.load(ModuleProvider.class);
    for (ModuleProvider provider : providers) {
        if (provider.getModuleName().equals("DBI")) {
            provider.initialize();
            break;
        }
    }
}
```

### Build Configuration

Maven pom.xml for DBI module:
```xml
<project>
    <artifactId>perlonjava-module-dbi</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.perlonjava</groupId>
            <artifactId>perlonjava-core</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
    
    <build>
        <resources>
            <resource>
                <directory>src/main/perl</directory>
                <includes>
                    <include>lib/DBI.pm</include>
                </includes>
            </resource>
            <resource>
                <directory>src/main/resources</directory>
                <includes>
                    <include>META-INF/services/**</include>
                </includes>
            </resource>
        </resources>
    </build>
</project>
```

Gradle build.gradle for DBI module:
```gradle
plugins {
    id 'java-library'
}

dependencies {
    implementation project(':perlonjava-core')
}

sourceSets {
    main {
        resources {
            srcDir 'src/main/perl'
            include 'lib/DBI.pm'
        }
    }
}
```

### Usage

Users can now:
1. Include just the core runtime for basic Perl execution
2. Add database support by including perlonjava-module-dbi.jar
3. Use `use DBI;` which triggers dynamic module loading

This modular approach:
- Keeps the core runtime lean
- Makes extensions pluggable
- Maintains clean separation of concerns
- Supports both Maven and Gradle builds
- Preserves the existing `use` statement behavior

# Project Structure Reorganization

## Proposed Directory Layout

```
perlonjava/                  # Main runtime
  src/
    main/
      java/
        org/perlonjava/
          parser/
          runtime/
      perl/
        lib/
  pom.xml
  build.gradle

perlonjava-module-dbi/       # DBI module
  src/
    main/
      java/
        org/perlonjava/module/dbi/
      perl/
        lib/
  pom.xml
  build.gradle

settings.gradle             # Root Gradle settings
pom.xml                    # Root Maven POM
```

## Benefits

1. Consistent naming and structure across all components
2. Clear separation between core runtime and optional modules  
3. Easier to add new modules following the same pattern
4. Standard Maven/Gradle multi-module project layout
5. Better IDE integration with standard Java project structure

## Implementation Steps

1. Create new `perlonjava/` directory
2. Move existing source files maintaining their package structure
3. Update build files for multi-module setup
4. Create new module directories following same pattern



# Root Build Configuration

The root build configurations that will build all subprojects:

## Maven Root pom.xml
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>org.perlonjava</groupId>
    <artifactId>perlonjava-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>perlonjava</module>
        <module>perlonjava-module-dbi</module>
    </modules>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <asm.version>9.7.1</asm.version>
        <junit.version>5.11.4</junit.version>
        <icu4j.version>76.1</icu4j.version>
        <fastjson.version>2.0.54</fastjson.version>
        <snakeyaml.version>2.9</snakeyaml.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.ow2.asm</groupId>
                <artifactId>asm</artifactId>
                <version>${asm.version}</version>
            </dependency>
            <dependency>
                <groupId>org.ow2.asm</groupId>
                <artifactId>asm-util</artifactId>
                <version>${asm.version}</version>
            </dependency>
            <dependency>
                <groupId>com.ibm.icu</groupId>
                <artifactId>icu4j</artifactId>
                <version>${icu4j.version}</version>
            </dependency>
            <dependency>
                <groupId>com.alibaba.fastjson2</groupId>
                <artifactId>fastjson2</artifactId>
                <version>${fastjson.version}</version>
            </dependency>
            <dependency>
                <groupId>org.snakeyaml</groupId>
                <artifactId>snakeyaml-engine</artifactId>
                <version>${snakeyaml.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

## Gradle Root settings.gradle
```gradle
rootProject.name = 'perlonjava-parent'

include 'perlonjava'
include 'perlonjava-module-dbi'
```

## Gradle Root build.gradle
```gradle
plugins {
    id 'java'
    id 'com.github.ben-manes.versions' version '0.51.0' apply false
    id 'se.patrikerdes.use-latest-versions' version '0.2.18' apply false
    id 'com.github.johnrengelman.shadow' version '8.1.1' apply false
}

subprojects {
    apply plugin: 'java'
    
    group = 'org.perlonjava'
    version = '1.0-SNAPSHOT'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType(JavaCompile).configureEach {
        options.compilerArgs << '-Xlint:-options'
        options.compilerArgs << '-Xlint:deprecation'
    }
}

ext {
    asmVersion = '9.7.1'
    icu4jVersion = '76.1'
    fastjsonVersion = '2.0.54'
    snakeyamlVersion = '2.9'
    junitVersion = '5.9.2'
}
```

This configuration:
1. Defines common properties and versions
2. Sets up dependency management
3. Configures common build settings
4. Allows building all modules with a single command:
   - Maven: `mvn clean install`
   - Gradle: `./gradlew build`

The subprojects can now reference these shared configurations while maintaining their specific build needs.

The build creates multiple JAR files in target/:

```
target/
  perlonjava-1.0-SNAPSHOT.jar          # Core runtime
  perlonjava-module-dbi-1.0-SNAPSHOT.jar   # DBI module
```

This modular structure lets users:
1. Run basic Perl scripts with just the core runtime
2. Add database support by including the DBI module
3. Keep deployments minimal and focused

The shadow plugin bundles dependencies into executable JARs, making distribution and execution straightforward.



# Testing Extensions

## Testing Strategy

### Test Structure
```
perlonjava-module-name/
  src/
    test/
      resources/
        module_name.t      # Perl test file
      java/
        org/perlonjava/module/
          ModuleTest.java  # Java integration tests
```

### Maven Configuration

Add to module's pom.xml:
```xml:perlonjava-module-name/pom.xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>3.2.5</version>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Gradle Configuration

Add to module's build.gradle:
```gradle:perlonjava-module-name/build.gradle
tasks.register('perlTest', JavaExec) {
    dependsOn tasks.testClasses
    group = 'Verification'
    description = 'Runs Perl integration tests'
    
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'org.perlonjava.test.PerlTestRunner'
    
    systemProperty 'test.perl.files', fileTree(dir: 'src/test/resources', include: '*.t')
}

test.dependsOn perlTest
```

### Example Test File (YAML::PP)

Based on the existing yaml_pp.t test file pattern:

```perl:perlonjava-module-yaml/src/test/resources/yaml_pp.t
use feature 'say';
use strict;
use YAML::PP;

my $ypp = YAML::PP->new();
print "not " unless defined $ypp;
say "ok # YAML::PP constructor";

# Additional tests...
```

### Test Runner Implementation

```java:perlonjava/src/main/java/org/perlonjava/test/PerlTestRunner.java
public class PerlTestRunner {
    public static void main(String[] args) {
        String testFiles = System.getProperty("test.perl.files");
        PerlEngine engine = new PerlEngine();
        
        for (String file : testFiles.split(File.pathSeparator)) {
            System.out.println("Running " + file);
            try {
                engine.executeFile(new File(file));
            } catch (Exception e) {
                System.err.println("Test failed: " + file);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}
```

### Running Tests

Maven:
```bash
mvn verify
```

Gradle:
```bash
./gradlew test
```

This approach:
- Uses standard build tool test infrastructure
- Integrates with CI/CD pipelines
- Provides detailed test reporting
- Supports both unit and integration tests
- Maintains Perl's test style while running in JVM
- Allows testing modules with full runtime context

The test results appear in standard Maven/Gradle test reports, making them easy to integrate with existing development workflows.
