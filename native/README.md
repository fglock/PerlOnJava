# Native launcher shim

PerlOnJava normally needs no C compiler and does not execute native Perl or
XS code. This directory contains one small, optional macOS C program:
`jperl-exec`.

## Why it exists

Perl programs sometimes generate another executable script with this shebang:

```text
#!$^X
```

For PerlOnJava, `$^X` is ordinarily the `jperl` Bash launcher. macOS does not
reliably execute a script whose shebang points to another script. Directly
running the generated script can therefore fail, even though
`jperl generated-script` works.

`jperl-exec` is a real executable, so the kernel can use it as the first
shebang interpreter. It locates the adjacent `jperl` launcher (or honors
`PERLONJAVA_EXECUTABLE`) and `exec`s `/bin/bash jperl` with the original
arguments. The usual launcher then starts the Java runtime. It contains no
Perl implementation, JNI, or native library binding.

## Optional build behavior

Only on macOS, the Gradle build compiles `jperl-exec` with `$CC`, or `cc` when
`$CC` is unset, if that compiler is available. It is installed beside `jperl`
and PerlOnJava exposes it as `$^X`; direct generated shebang scripts then
work.

If no compiler is found, the macOS build continues without the shim.
PerlOnJava, `jperl`, `jcpan`, and scripts invoked as `jperl script.pl`
continue to work; only direct execution of generated `#!$^X` scripts is
unavailable. The corresponding macOS capability test is omitted in that
configuration.

Linux has supported a script interpreter that is itself a script since kernel
2.6.28, so it needs no shim or C compiler for this behavior. Windows does not
build the macOS shim.

To deliberately omit it even when a compiler is installed:

```bash
make build GRADLE_ARGS=-PskipNativeJperlLauncher
```

To select a compiler explicitly:

```bash
CC=clang make build
```

The compiled development artifact is `target/jperl-exec`. Packaged macOS
installations place it next to `jperl` in `bin/`.
