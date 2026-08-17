### Relation with the Perlito compiler

The key difference between PerlOnJava and Perlito (https://github.com/fglock/Perlito) is in their compilation approach. Perlito is a bootstrapped Perl compiler, written in Perl, which compiles Perl code to Java and then to bytecode. PerlOnJava, on the other hand, is a native Perl compiler for the JVM that directly generates Java bytecode using the ASM library. This approach makes PerlOnJava more efficient, particularly in terms of eval execution speed, and results in smaller jar files, leading to faster startup times.

From an architectural standpoint, PerlOnJava is more mature. However, Perlito is currently more feature-rich due to its longer development history. PerlOnJava, however, doesn't support JavaScript like Perlito does.

Both compilers inherit constraints from the JVM, but PerlOnJava's current
limitations are narrower than a blanket “no XS or automatic close” statement.
Native C/XS binaries cannot run, while documented modules use Java replacements
or pure-Perl fallbacks. Lexical filehandle cleanup, including descriptor closure,
works on the JVM backend; the interpreter backend's descriptor closure remains
incomplete. PerlOnJava implements `DESTROY` via selective reference counting.
