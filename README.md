# PerlOnJava Perl Compiler

This project presents a Perl compiler that compiles Perl into Java bytecode and runs it, providing a method to integrate Perl with Java-based ecosystems.

## Table of Contents

1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Features and Limitations](#features-and-limitations)
4. [Target Audience](#target-audience)
5. [Build Instructions](#build-instructions)
6. [Running the JAR File](#running-the-jar-file)
7. [Debugging Tools](#debugging-tools)
8. [Architecture](docs/ARCHITECTURE.md)
9. [Milestones](MILESTONES.md)
10. [Support and Contribution Guide](docs/SUPPORT.md)
11. [Community](#community)
12. [License](#license)
13. [Additional Information and Resources](#additional-information-and-resources)

## Introduction

This project aims to develop a Perl compiler that translates Perl code into Java bytecode and executes it on the Java Virtual Machine (JVM). It provides a platform for running Perl scripts in a JVM environment, facilitating integration between Perl and Java.

## Quick Start

1. Build the project:
```bash
mvn clean package
```

2. Run a simple Perl script:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar -E 'print "Hello from Perl!\n"'
```

3. Use Perl in your Java application:
```java
import javax.script.*;

ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine engine = manager.getEngineByName("perl");
engine.eval("print 'Hello from Java-integrated Perl!\n'");
```

4. Connect to a database:
```perl
use DBI;
my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
$dbh->do("CREATE TABLE test (id INT, name VARCHAR(50))");
$dbh->do("INSERT INTO test VALUES (1, 'Hello World')");
```

## Features and Limitations

### Features

- **Closures**: Supports anonymous functions and lexical variable closures.
- **Eval-string**: Executes Perl code dynamically.
- **Statements, Data Types, and Call Context**: Handles common Perl statements, data types, and maintains Perl's context sensitivity.
- **Java Scripting API (JSR 223)**: Enables Perl scripts to be executed within Java applications using the `ScriptEngine` interface.

For a detailed feature list, see the [FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md).

### Current Limitations

- **Limited Module Support**: Does not support XS modules or many CPAN libraries.
- **Syntax Compatibility**: May not fully support advanced syntax-changing modules or esoteric Perl features.

### What This Project Is

- **A Complement to Perl**: This project complements Perl by enabling its integration with Java environments.

## Target Audience

- **Java Developers with Perl Knowledge**: Provides a method for integrating Perl scripts into Java applications.
- **Compiler and Language Enthusiasts**: Offers insights into translating high-level languages into JVM bytecode.
- **Experimenters and Tinkerers**: A tool for experimenting with language interoperability.

## Build Instructions

### Compile and Package with Maven

1. **Ensure you have Maven installed**:
   - You can download and install Maven from [Maven's official website](https://maven.apache.org/).

2. **Compile and Package the Project**:
   - Run the following Maven command to compile and package the project into a shaded JAR:

    ```bash
    mvn clean package
    ```

3. **Run the JAR**:
   - After packaging, you can run the JAR file with:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar
    ```

### Compile and Package with Gradle

1. **Ensure you have Gradle installed**:
   - You can download and install Gradle from [Gradle's official website](https://gradle.org/).

2. **Compile and Package the Project**:
   - Run the following Gradle command to compile and package the project into a shaded JAR:

    ```bash
    gradle clean build
    ```

3. **Run the JAR**:
   - After packaging, you can run the JAR file with:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar
    ```

### Dependencies

- **JUnit**: For testing.
- **ASM**: For bytecode manipulation.
- **ICU4J**: For Unicode support.
- **FASTJSON v2**: For JSON support.

### Adding JDBC Drivers

JDBC Database drivers can be added in two ways:

1. Using Configure.pl:
    ````bash
    ./Configure.pl --search mysql-connector-java
    ````

2. Using Java classpath:
    ````bash
    java -cp "jdbc-drivers/h2-2.2.224.jar:target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main myscript.pl
    ````

For detailed instructions and database connection examples, see [JDBC Database Guide](docs/JDBC_GUIDE.md).


### Notes

- The project uses the `maven-shade-plugin` for Maven to create a shaded JAR.
- The project uses the `com.github.johnrengelman.shadow` plugin for Gradle to create a shaded JAR.
- Both Maven and Gradle configurations are set to use Java 21.

## Running the JAR File

1. **Show Instructions**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --help
    ```

2. **Execute Something**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -E ' print 123 '
    ```

Setting `lib` path with `-I` to access Perl modules is optional. Standard modules are included in the jar file.

## Debugging Tools

1. **Execute Emitting Debug Information**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -E ' print 123 '
    ```

2. **Compile Only; Can Be Combined with --debug**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar -c -E ' print 123 '
    ```

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug -c -E ' print 123 '
    ```

3. **Execute and Emit Disassembled ASM Code**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --disassemble -E ' print 123 '
    ```

4. **Run the Lexer Only**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --tokenize -E ' print 123 '
    ```

5. **Run the Parser Only**:

    ```bash
    java -jar target/perlonjava-1.0-SNAPSHOT.jar --parse -E ' print 123 '
    ```

## Community

### Getting Help
- GitHub Issues: Technical questions and bug reports
- GitHub Discussions: General project discussions and feature requests
- Stack Overflow: Use tags `perl` and `perlonjava`

### Contributing
- Fork the repository and submit pull requests
- Check [Support and Contribution Guide](docs/SUPPORT.md) for detailed guidelines
- Join discussions on upcoming features and improvements

### Stay Updated
- Watch the repository for release notifications
- Follow project announcements in GitHub Discussions
- Check [MILESTONES.md](MILESTONES.md) for roadmap updates

### Report Issues
Submit bug reports with:
- PerlOnJava version
- Java version
- Operating system
- Minimal reproducible example
- Expected vs actual behavior


## License

This project is licensed under the Perl Artistic License 2.0 - see the [LICENSE](LICENSE.md) file for details.

## Additional Information and Resources


- Brian Jepson in TPJ, 1997 [JPL: The Java-Perl Library](https://www.foo.be/docs/tpj/issues/vol2_4/tpj0204-0003.html).

- Kuhn, Bradley M. (January 2001). [Considerations on Porting Perl to the Java Virtual Machine](http://www.ebb.org/bkuhn/writings/technical/thesis/) (MS thesis). University of Cincinnati.

- Bradley Kuhn, 2002 [perljvm: Using B to Facilitate a Perl Port To the Java Virtual Machine](https://www.ebb.org/bkuhn/articles/perljvm.html).

- Ehud Lamm, 2004 [Tim Bray: Sun & Dynamic Java](http://lambda-the-ultimate.org/node/426).

- Ben Evans in perl5.porters, 2009 [p5 on the JVM](https://www.nntp.perl.org/group/perl.perl5.porters/2009/08/msg150099.html).

- Nikos Vaggalis, 2013 [All about Perl 6 – interview of Flávio Glock](http://www.josetteorama.com/all-about-perl-6-interview-of-flavio-glock/).

- Andrew Shitov, 2015 [Interview with Flávio Glock](https://andrewshitov.com/2015/05/15/interview-with-flavio-glock/).

- Nikos Vaggalis, 2024 [Flavio Glock On Perl, Java, Compilers And Virtual Machines](https://www.i-programmer.info/professional-programmer/103-i-programmer/17491-flavio-glock-on-perl-java-compilers-and-virtual-machines.html).

- For more details on the relationship with the ["Perlito5" Perl to Java compiler and Perl to JavaScript compiler](https://github.com/fglock/Perlito), including its influence on this project and how it complements the PerlOnJava Perl Compiler, see [RELATION_WITH_PERLITO_COMPILER.md](docs/RELATION_WITH_PERLITO_COMPILER.md). This document provides insights into the historical context and technical decisions that shaped the development of this compiler.


![Java CI with Maven](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Maven/badge.svg)

