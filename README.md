# PerlOnJava: A Perl Distribution for the JVM

PerlOnJava provides a Perl distribution designed to run natively on the Java Virtual Machine (JVM).
It allows Perl scripts to integrate seamlessly with Java-based ecosystems while offering familiar tools and modules for Perl development.

The JAR package features a variety of Perl modules, such as `DBI` (with JDBC support), `HTTP::Tiny`, `JSON`, `File::Find`, and `Data::Dumper`.
Users can also add their own database JDBC drivers, making it a flexible solution for cross-platform Perl applications.

## Table of Contents

1. [Introduction](#introduction)
2. [Why PerlOnJava](docs/WHY_PERLONJAVA.md)
3. [Target Audience](#target-audience)
4. [Quick Start](#quick-start)
5. [Features and Limitations](docs/FEATURE_MATRIX.md)
6. [Build Instructions](docs/BUILD.md)
7. [Running the JAR File](#running-the-jar-file)
8. [Debugging Tools](#debugging-tools)
9. [Architecture](docs/ARCHITECTURE.md)
10. [Porting Modules](docs/PORTING_MODULES.md)
11. [Milestones](MILESTONES.md)
12. [Community and Support](docs/SUPPORT.md)
13. [License](#license)
14. [Additional Resources](docs/RESOURCES.md)

## Introduction

PerlOnJava bridges the gap between Perl and Java by providing a platform that compiles Perl scripts into Java bytecode, making them executable on the JVM.
By leveraging this distribution, developers can run familiar Perl code while accessing Java's ecosystem of libraries and tools.
This project aims to bring the strengths of Perl to modern JVM environments while supporting a growing list of core modules and pragmas.

Need help? Check out our [Community and Support](docs/SUPPORT.md) section.

### What This Project Is

- **A JVM-Native Perl Implementation**: Runs Perl code directly on the Java Virtual Machine
- **A Bridge to Java Ecosystems**: Enables Perl scripts to interact with Java libraries and frameworks
- **A Cross-Platform Solution**: Provides consistent Perl behavior across different operating systems via JVM
- **A Modern Integration Tool**: Allows Perl codebases to participate in Java-based enterprise environments

## Target Audience

- **Java Developers with Perl Knowledge**: Provides a method for integrating Perl scripts into Java applications.
- **Compiler and Language Enthusiasts**: Offers insights into translating high-level languages into JVM bytecode.
- **Experimenters and Tinkerers**: A tool for experimenting with language interoperability.

## Quick Start

Get started quickly with PerlOnJava. For a complete list of capabilities, see our [Feature Matrix](docs/FEATURE_MATRIX.md).

1. Build the project ([detailed instructions](docs/BUILD.md)):
   ```bash
   mvn clean package
   ```

2. Run a simple Perl script:

   Unix/Linux/Mac:
   ```bash
   ./jperl -E 'say "Hello World"'
   ```

   Windows:
   ```bash
   jperl -E "say 'Hello World'"
   ```

3. Use Perl in your Java application:
   ```java
   import javax.script.*;
   
   public class TestPerl {
      public static void main(String[] args) throws Exception {
         ScriptEngineManager manager = new ScriptEngineManager();
         ScriptEngine engine = manager.getEngineByName("perl");
         engine.eval("print 'Hello from Java-integrated Perl!\n'");
      }
   }
   ```

4. Connect to a database:
   ```perl
   use DBI;
   my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
   $dbh->do("CREATE TABLE test (id INT, name VARCHAR(50))");
   $dbh->do("INSERT INTO test VALUES (1, 'Hello World')");
   ```

## Running the JAR File

1. **Show Instructions**:
    ```bash
    ./jperl --help
    ```

2. **Execute Something**:
    ```bash
    ./jperl -E 'print 123'
    ```

Setting `lib` path with `-I` to access Perl modules is optional. Standard modules are included in the jar file.

## Debugging Tools

1. **Execute Emitting Debug Information**:
    ```bash
    ./jperl --debug -E 'print 123'
    ```

2. **Compile Only; Can Be Combined with --debug**:
    ```bash
    ./jperl -c -E 'print 123'
    ```
    ```bash
    ./jperl --debug -c -E 'print 123'
    ```

3. **Execute and Emit Disassembled ASM Code**:
    ```bash
    ./jperl --disassemble -E 'print 123'
    ```

4. **Run the Lexer Only**:
    ```bash
    ./jperl --tokenize -E 'print 123'
    ```

5. **Run the Parser Only**:
    ```bash
    ./jperl --parse -E 'print 123'
    ```

## License

This project is licensed under the Perl Artistic License 2.0 - see the [LICENSE](LICENSE.md) file for details.

![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)
