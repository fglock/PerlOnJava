### Introduction

PerlOnJava brings Perl 5 to JVM-based environments where integration with Java
libraries, deployment tooling, or runtime infrastructure matters. Its broad
language implementation is in place, and the project is actively working
through the remaining compatibility, CPAN ecosystem, and performance gaps. See
the [current project status](roadmap.md#current-project-status) and
[feature matrix](../reference/feature-matrix.md) before choosing it for a
specific workload.

Traditional Perl remains the best default for projects that do not need JVM
integration: its implementation and CPAN ecosystem are more mature, and native
XS extensions work directly. The right choice depends on the required modules,
platform constraints, and Java interoperability needs.


### Why Use PerlOnJava?

1. **JVM Ecosystem Integration**:  
   PerlOnJava enables seamless integration of Perl scripts within Java environments, leveraging the rich ecosystem of Java libraries and tools. This is particularly advantageous for developers or organizations already invested in JVM-based infrastructures.

2. **Cross-Platform Compatibility**:  
   Running on the JVM ensures consistent behavior across platforms supported by Java, eliminating many platform-specific issues that arise with traditional Perl.

3. **Modular Architecture**:  
   PerlOnJava’s architecture facilitates integration with other JVM-based languages (e.g., Kotlin, Scala) and provides tools like JDBC for database interaction, making it ideal for enterprise environments.

4. **JVM Performance Optimizations**:
   The project uses modern Java and ASM techniques to optimize execution. The JVM's just-in-time (JIT) compilation and garbage collection can provide performance benefits for compute-intensive tasks.

5. **Compiler and Runtime Research**:
   PerlOnJava is also a useful resource for understanding compiler design,
   language interoperability, and the translation of a dynamic language to
   bytecode.

6. **Customizability**:  
   Developers can modify and extend the compiler to meet specific requirements, benefiting from JVM's debugging and profiling tools.


### Why Not Use PerlOnJava?

1. **Mature Ecosystem of Traditional Perl**:  
   Perl’s implementation in C is stable, mature, and battle-tested over decades, offering extensive CPAN support. PerlOnJava cannot load native XS (C extension) binaries directly, although it bundles Java implementations for a growing set of commonly used XS modules.

2. **Feature Limitations**:  
   PerlOnJava does not yet support every advanced Perl feature or CPAN module. Pure-Perl modules have the best compatibility; XS modules require a bundled or separately developed Java implementation.

3. **Portability Concerns**:  
   While JVM provides cross-platform support, running PerlOnJava requires a JVM installation, which might not be available in lightweight or resource-constrained environments where traditional Perl could run natively.

4. **Learning Curve**:  
   Adopting PerlOnJava might require developers to learn JVM concepts and manage Java dependencies, which adds complexity compared to using standard Perl.

5. **Community and Ecosystem**:  
   The community and ecosystem around PerlOnJava are smaller than those of traditional Perl, potentially limiting support and available resources.
