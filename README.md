# PerlOnJava: A Perl Distribution for the JVM

PerlOnJava provides a Perl distribution designed to run natively on the Java Virtual Machine (JVM). 
It allows Perl scripts to integrate seamlessly with Java-based ecosystems while offering familiar tools and modules for Perl development.

The JAR package features a variety of Perl modules, such as `DBI` (with JDBC support), `HTTP::Tiny`, `JSON`, `File::Find`, and `Data::Dumper`. 
Users can also add their own database JDBC drivers, making it a flexible solution for cross-platform Perl applications.

## Table of Contents

1. [Introduction](#introduction)
2. [Why PerlOnJava](docs/WHY_PERLONJAVA.md)
3. [Quick Start](#quick-start)
4. [Features and Limitations](#features-and-limitations)
5. [Target Audience](#target-audience)
6. [Build Instructions](#build-instructions)
7. [Running the JAR File](#running-the-jar-file)
8. [Debugging Tools](#debugging-tools)
9. [Architecture](docs/ARCHITECTURE.md)
10. [Porting Modules](docs/PORTING_MODULES.md)
11. [Milestones](MILESTONES.md)
12. [Community and Support](#community-and-support)
13. [License](#license)
14. [Additional Information and Resources](#additional-information-and-resources)

## Introduction

PerlOnJava bridges the gap between Perl and Java by providing a platform that compiles Perl scripts into Java bytecode, making them executable on the JVM. 
By leveraging this distribution, developers can run familiar Perl code while accessing Java's ecosystem of libraries and tools. 
This project aims to bring the strengths of Perl to modern JVM environments while supporting a growing list of core modules and pragmas.
For detailed reasoning on when to use PerlOnJava, see [Why PerlOnJava](docs/WHY_PERLONJAVA.md).

### What This Project Is

- **A JVM-Native Perl Implementation**: Runs Perl code directly on the Java Virtual Machine
- **A Bridge to Java Ecosystems**: Enables Perl scripts to interact with Java libraries and frameworks
- **A Cross-Platform Solution**: Provides consistent Perl behavior across different operating systems via JVM
- **A Modern Integration Tool**: Allows Perl codebases to participate in Java-based enterprise environments


## Quick Start

1. Build the project:
   ```bash
   mvn clean package
   ```

2. Run a simple Perl script:

   Unix/Linux/Mac:
   ```bash
   ./jperl -E 'print "Hello World"'
   ```

   Windows:
   ```bash
   jperl -E "print 'Hello World'"
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

## Features and Limitations

### Key Features

- **Core Perl Language**: Full support for variables, loops, conditionals, subroutines, and most operators
- **Object-Oriented Programming**: Classes, inheritance, method calls, and C3 method resolution
- **Regular Expressions**: Comprehensive regex support including most modifiers and special variables
- **Java Integration**:
   - Execute Perl scripts in Java environments via JSR 223
   - DBI module with native JDBC support
   - Cross-platform consistency through JVM

### Core Module Support
- **Data Processing**: `Data::Dumper`, `JSON`, `Text::Balanced`
- **File Operations**: `File::Find`, `File::Basename`, `File::Spec`
- **Network**: `HTTP::Tiny`, `HTTP::CookieJar`, `URI::Escape`
- **Development**: `Carp`, `Symbol`, `Exporter`

### Current Limitations

- **Module Compatibility**: No support for XS modules or direct C integration
- **System Operations**: Cannot use fork, exec, or low-level system calls
- **Advanced Features**: No support for threads, Unicode manipulation, or tied variables

### Detailed Documentation

For a comprehensive overview of supported features, limitations, and implementation details, see our [Feature Matrix](docs/FEATURE_MATRIX.md).


## Target Audience

- **Java Developers with Perl Knowledge**: Provides a method for integrating Perl scripts into Java applications.
- **Compiler and Language Enthusiasts**: Offers insights into translating high-level languages into JVM bytecode.
- **Experimenters and Tinkerers**: A tool for experimenting with language interoperability.

## Build Instructions

See [Build and Execution Guide](docs/BUILD.md).

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
   
## Community and Support

### Quick Links
- [Support and Contribution Guide](docs/SUPPORT.md) - Detailed guidelines for contributors
- [GitHub Issues](https://github.com/fglock/PerlOnJava/issues) - Bug reports and feature requests
- [GitHub Discussions](https://github.com/fglock/PerlOnJava/discussions) - Community discussions
- Stack Overflow - Use tags `perl` and `perlonjava`

### Getting Started with Contributing
1. Read the [Support and Contribution Guide](docs/SUPPORT.md)
2. Check [MILESTONES.md](MILESTONES.md) for project roadmap
3. Review [FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) for implementation status

### Release Updates
- Watch the repository for notifications
- Check [MILESTONES.md](MILESTONES.md) for version history
- Follow LTS version announcements


## License

This project is licensed under the Perl Artistic License 2.0 - see the [LICENSE](LICENSE.md) file for details.

## Additional Information and Resources

Please see [Additional Resources and References](docs/RESOURCES.md).

![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)

