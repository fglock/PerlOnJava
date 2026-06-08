# PerlOnJava

*"It shouldn't work. It does work. These two facts have not yet been introduced to each other."*

---

## What Is This Thing?

Somewhere between a clever idea and a cry for help, **PerlOnJava** compiles Perl to JVM bytecode.

Yes, *that* Perl. The one with the `$`, `@`, and `%` sigils that look like someone sneezed on a shift key. And yes, *that* JVM — the one that runs inside roughly 97% of enterprise software, approximately 40% of which nobody has touched since 2009.

Together, they form something that computer scientists will one day study, in the same hushed tones reserved for the Antikythera mechanism and the Winchester Mystery House.

---

## Why?

This is, admittedly, a question that comes up a lot.

The honest answer is: because Perl is extraordinarily good at things that Java finds deeply uncomfortable — text processing, regular expressions, rapid scripting, a certain devil-may-care attitude toward types — and Java is extraordinarily good at things Perl finds uncomfortable, such as *running on anything built after 2005 without a three-page installation ritual.*

PerlOnJava is the arranged marriage nobody asked for and everybody quietly benefits from.

---

## Features

**A single JAR.** That's it. One file. You can put it in a drawer. You can email it. You can, theoretically, throw it at someone, though we accept no liability for the results. The entire Perl runtime, a standard library, and enough ambition to make sensible people nervous — all in one tidy archive.

**Bundled modules.** The most useful Perl modules are included, because we are not monsters. You should not have to fight CPAN simply to read a file.

**DBI via JDBC.** You can talk to databases. Real databases. With SQL. The kind of databases that have been running payroll since before you were born and will continue to do so long after everyone has forgotten what they contain or why.

**JSR-223 embedding.** This means you can run Perl *inside* Java programs. We understand if you need a moment.

**HTTP via Netty.** A fast, non-blocking HTTP stack, because sometimes a Perl script needs to be a web service, and the universe is under no obligation to make sense.

**Regex. Glorious, terrifying regex.** Perl's regular expressions are the reason the phrase "write-only code" exists. They are also the reason complex text problems get solved in one line instead of forty. PerlOnJava brings them to the JVM intact, like archaeological treasures that are still somehow dangerous.

---

## Getting Started

```bash
java -jar perlonjava.jar myscript.pl
```

That's it. The JAR will do the rest. It will parse your Perl, construct an abstract syntax tree, lower it into JVM bytecode, and execute it — all without once asking you how your day is going or suggesting you might prefer Kotlin.

---

## A Note on Compatibility

PerlOnJava aims for broad compatibility with standard Perl behaviour. "Broad compatibility" is, admittedly, a phrase that leaves some room. Perl has been accumulating behaviour since 1987. Some of that behaviour was intentional. PerlOnJava handles the vast majority of it, and is improving continuously, which is more than can be said for several programming languages currently drawing a salary in production systems worldwide.

---

## A Note on Regular Expressions

They work. All of them. Even the ones you wrote at 2am that you no longer understand. *Especially* those.

---

## Contributing

Contributions are welcome. PerlOnJava is open source, which means it belongs, in a philosophical sense, to everyone, and in a practical sense, to whoever fixes the most bugs.

If you find something broken, please open an issue. If you know how to fix it, please open a pull request. If you have found something that *shouldn't* work but does, please open a pull request and also perhaps lie down for a bit.

---

## Licence

Open source. See `LICENSE` for the legal specifics, which are considerably less entertaining than this document but considerably more binding.

---

*PerlOnJava: Because the right tool for the job is occasionally two tools duct-taped together by someone who really knew what they were doing.*
