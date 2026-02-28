# PerlOnJava

## Perl Running Natively on the JVM

German Perl/Raku Workshop 2026

Flavio Glock

*Part 1: Introduction and Live Demo (20 minutes)*

---

## The Problem

Imagine **50,000 lines of Perl** — and a mandate to move to Java or Kubernetes.

**Your options today:**
- Rewrite everything in Java
- Maintain a separate Perl runtime forever

**What if there were a third option?**

Note:
This is a common scenario in enterprise environments. Many companies have large Perl codebases that work well but don't fit into modern Java-based infrastructure.

---

## PerlOnJava: The Third Option

<span class="metric">A Perl compiler and runtime for the JVM</span>

- Compiles Perl to **native JVM bytecode**
- Same format as Java, Kotlin, Scala
- Not an interpreter wrapping a Perl binary
- Targets Perl 5.42+ semantics

**Requires:** Java 21+ (any JDK)

Note:
PerlOnJava generates real JVM bytecode — the same kind of instructions that javac produces. This means Perl code gets all the JVM benefits: cross-platform, Java library access, JVM tooling.

---

## What You Get

- Run existing Perl scripts **unchanged** on the JVM
- Access any **JDBC database** — no C drivers needed
- Embed Perl in Java apps via **JSR-223** scripting API
- Deploy to Docker, Kubernetes — **anywhere Java runs**

Note:
JSR-223 is the standard Java scripting API, available since Java 6. It allows bidirectional Java ↔ Perl communication.

---

## 25 Years in the Making

**1997** — JPL (Java-Perl Library)

**2001** — Bradley Kuhn's MS thesis on Perl-to-JVM

**2002** — perljvm prototype

**2013–2023** — Perlito5: Perl to Java/JS compiler

**2024** — PerlOnJava: lessons learned, production focus

Note:
Each prior attempt informed this implementation. JPL showed embedding was possible. Kuhn mapped the type system challenges. Perlito5 proved compilation works but revealed startup and eval limitations. PerlOnJava addresses all of these.

---

## By the Numbers

- <span class="metric">~260,000 tests</span> in the suite
- <span class="metric">392 Java source files</span>
- <span class="metric">5,741 commits</span> since June 2024
- <span class="metric">2.2× faster</span> than Perl 5 on loop benchmarks

No formal Perl spec exists — the test suite **is** the specification.

---

## Bundled Ecosystem

<span class="metric">341 Perl modules</span> ship inside the JAR

DBI, HTTP::Tiny, JSON, YAML, Text::CSV, Digest::MD5, MIME::Base64…

- **Pure-Perl CPAN modules** work as-is
- **XS modules** use Java equivalents (many bundled)
- Modern Perl `class` features (OOP)
- Dual execution backend (JVM + Internal VM)

---

## Getting Started

```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests (~5 minutes)
./jperl -E 'say "Hello from PerlOnJava!"'
```

Note:
Add Maven dependencies with: `./Configure.pl --search mysql-connector-java`. Pure-Perl CPAN modules work as-is. XS modules need Java equivalents — many are already bundled.

---

## Live Demo

```bash
# Conway's Game of Life
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl

# Database access with DBI
./jperl examples/dbi.pl
```

---

## Live Demo: Database Integration

**DBI with JDBC — no C-based DBD adapters needed:**

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

Note:
Supports PostgreSQL, MySQL, Oracle, SQLite, H2 — any JDBC driver.

---

## How It Works: AST

**Abstract Syntax Tree** — tree representation of source code

`my $x = 1 + 2` becomes:

```
assignment → addition → operands
```

The compiler walks this tree to generate bytecode.

---

## How It Works: JVM Bytecode

Low-level instructions for the Java Virtual Machine

- Same format as Java, Kotlin, Scala
- The JVM's **JIT compiler** turns hot bytecode into native machine code

PerlOnJava emits bytecode directly via the **ASM library** — no Java source intermediate step.

---

## The Compilation Pipeline

```
Perl Source → Compiler → JVM Bytecode → JVM Execution
                       ↘ Custom Bytecode → Internal VM
```

**Five stages:** Lexer → Parser → StringParser → EmitterVisitor → ClassLoader

---

## Two Backends, One Runtime

- **JVM backend** — maximum performance via JIT optimization
- **Internal VM** — compact, no size limits, fast `eval STRING`

Both share **100% of the runtime**. User code doesn't know which backend is running.

Note:
The dual backend is a common VM design pattern. HotSpot, V8, SpiderMonkey, and CRuby all use tiered execution for similar reasons.

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

Note:
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

Register-based, ~200 opcodes — much more compact.

---

## What's Next: Part 2

**Technical deep-dive (40 minutes):**

- The compilation pipeline in detail
- Why two backends — and when each one wins
- JVM optimization techniques applied to Perl
- Implementing complex Perl semantics on the JVM
- Integration and future plans

---
