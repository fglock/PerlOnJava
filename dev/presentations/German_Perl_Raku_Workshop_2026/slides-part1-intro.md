# PerlOnJava

## Perl Running Natively on the JVM

German Perl/Raku Workshop 2026

Flavio Glock

*Part 1: Introduction and Live Demo (20 minutes)*

---

## What is Perl?

- High-level language created by Larry Wall in 1987
- Text processing, sysadmin, web, databases
- Comprehensive module ecosystem (CPAN)
- "Makes easy things easy and hard things possible"
- Still widely used in enterprise, bioinformatics, finance, DevOps

---

## What is PerlOnJava?

<span class="metric">A Perl compiler and runtime for the JVM</span>

- Compiles Perl to **native JVM bytecode** — same format as Java, Kotlin, Scala
- Not an interpreter wrapping a Perl binary
- Targets Perl 5.42+ semantics
- Gains JVM benefits: cross-platform, Java library access, JVM tooling

**Targets:** Java 21+ (any JDK)

---

## Historical Context

**25+ years of Perl-on-JVM research:**

- **1997**: JPL (Java-Perl Library) — Brian Jepson
- **2001**: Bradley Kuhn's MS thesis on porting Perl to JVM
- **2002**: perljvm prototype using Perl's B compiler
- **2013–2023**: Perlito5 — Perl to Java/JavaScript compiler (same author)
- **2024**: PerlOnJava — production-oriented compiler (active development)

Each prior attempt informed this implementation. PerlOnJava is the culmination of lessons learned.

---

## Why Does This Matter?

You have **50,000 lines of Perl**. Your company is moving to Java/Kubernetes.

**Without PerlOnJava:** Rewrite everything, or maintain a separate Perl runtime.

**With PerlOnJava:**
- Run existing Perl scripts unchanged on the JVM
- Access any JDBC database — no C drivers needed
- Embed Perl in Java apps via the standard JSR-223 scripting API
- Deploy to Docker, Kubernetes — anywhere Java runs

---

## Key Achievements

- <span class="metric">~260,000 tests</span> in the suite
- <span class="metric">392 Java source files</span>
- <span class="metric">341 bundled Perl modules</span>
- <span class="metric">5,741 commits</span> since June 2024
- <span class="metric">2.2× faster</span> than Perl 5 on loop benchmarks

**Bundled:** DBI, HTTP::Tiny, JSON, YAML, Text::CSV, Digest::MD5, MIME::Base64…

No formal Perl spec exists — the test suite is the de facto specification.

---

## Recent Milestones

- Latest Perl `class` features (modern OOP)
- Dual execution backend (JVM compiler + Internal VM)
- System V IPC and socket operations
- 260,000+ tests in the suite

---

## Getting Started

**Requirements:** Java 21+ (any JDK)

```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests (~5 minutes)
./jperl -E 'say "Hello from PerlOnJava!"'
```

**Add Maven dependencies (e.g. a JDBC driver):**
```bash
./Configure.pl --search mysql-connector-java
```

**Pure-Perl CPAN modules** work as-is. **XS modules** need Java equivalents (many bundled).

---

## Live Demo

```bash
# Simple hello world
./jperl -E 'say "Hello from PerlOnJava!"'

# Conway's Game of Life
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl

# Database access with DBI
./jperl examples/dbi.pl
```

---

## Live Demo: Database Integration

**DBI with JDBC — no C-based DBD::* adapters needed:**

```perl
use DBI;
my $dbh = DBI->connect(
    "dbi:SQLite:dbname=test.db", "", "",
    { RaiseError => 1 }
);
my $sth = $dbh->prepare("SELECT * FROM users");
$sth->execute();
while (my $row = $sth->fetchrow_hashref) {
    say "User: $row->{name}";
}
```

**Supports:** PostgreSQL, MySQL, Oracle, SQLite, H2, any JDBC driver

---

## How It Works: Key Concepts

**AST (Abstract Syntax Tree):** Tree representation of source code. `my $x = 1 + 2` becomes assignment → addition → operands. The compiler walks this tree to generate code.

**JVM Bytecode:** Low-level instructions for the Java Virtual Machine — same format as Java, Kotlin, Scala. The JVM's JIT compiler turns hot bytecode into native machine code at runtime.

**ASM library:** A Java library for generating JVM bytecode programmatically. PerlOnJava emits bytecode directly, without going through Java source.

---

## How It Works: The Pipeline

```
Perl Source → Compiler → JVM Bytecode → JVM Execution
                       ↘ Custom Bytecode → Internal VM
```

**Five stages:** Lexer → Parser → StringParser (DSLs) → EmitterVisitor (bytecode) → ClassLoader

**Two execution backends sharing 100% of the runtime:**
- **JVM backend** — maximum performance via JIT optimization
- **Internal VM** — compact, no size limits, fast `eval STRING`

User code doesn't know which backend is running.

---

## Seeing the Output: JVM Bytecode

**Perl:** `my $x = 10; say $x`

**Generated JVM bytecode** (`./jperl --disassemble`):
```
LDC 10
INVOKESTATIC RuntimeScalarCache.getScalarInt(I)
ASTORE 7
NEW RuntimeScalar
DUP
INVOKESPECIAL RuntimeScalar.<init>()
ASTORE 25
ALOAD 25
ALOAD 7
SWAP
INVOKEVIRTUAL RuntimeBase.addToScalar(RuntimeScalar)
...
INVOKESTATIC IOOperator.say(RuntimeList;RuntimeScalar)
```

Standard JVM bytecode — optimized by the JVM's JIT compiler at runtime.

---

## Seeing the Output: Custom Bytecode

**Same Perl:** `my $x = 10; say $x`

**Generated Internal VM bytecode** (`./jperl --interpreter --disassemble`):
```
Registers: 9
Bytecode length: 26 shorts

   0: LOAD_INT r4 = 10
   4: MOVE r3 = r4
   7: CREATE_LIST r6 = []
  10: SELECT r5 = select(r6)
  13: CREATE_LIST r7 = [r3]
  17: SAY r7, fh=r5
  20: LOAD_INT r8 = 1
  24: RETURN r8
```

Register-based, ~200 opcodes. Much more compact than JVM bytecode.

---

## What's Next: Part 2

**Technical deep-dive (40 minutes):**

- The compilation pipeline in detail
- Why two backends — and when each one wins
- JVM optimization techniques applied to Perl
- Implementing complex Perl semantics on the JVM
- Integration and future plans

---
