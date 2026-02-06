### Relation with the Perlito compiler

The key difference between PerlOnJava and Perlito (https://github.com/fglock/Perlito) is in their compilation approach. Perlito is a bootstrapped Perl compiler, written in Perl, which compiles Perl code to Java and then to bytecode. PerlOnJava, on the other hand, is a native Perl compiler for the JVM that directly generates Java bytecode using the ASM library. This approach makes PerlOnJava more efficient, particularly in terms of eval execution speed, and results in smaller jar files, leading to faster startup times.

From an architectural standpoint, PerlOnJava is more mature. However, Perlito is currently more feature-rich due to its longer development history. PerlOnJava, however, doesn't support JavaScript like Perlito does.

Both compilers share certain limitations imposed by the JVM, such as the lack of support for DESTROY, XS modules, and auto-closing filehandles, among others.

