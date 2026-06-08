# BEHOLD: PerlOnJava

*"There are two kinds of software engineering: carefully designed architecture, and finding out what happens if you pull this lever. PerlOnJava is interested in both."*

---

# What Is PerlOnJava?

PerlOnJava compiles Perl to JVM bytecode.

Yes, that Perl.

The one that regards punctuation not as decoration but as a management strategy.

And yes, that JVM.

The immense subterranean bureaucracy responsible for keeping the modern world functioning through a combination of strict rules, virtual machines, and application servers installed by people who have since retired.

Against all expectation, the arrangement works.

This has disappointed several theorists.

---

# Why?

Because Perl and Java have complementary personalities.

Perl is the eccentric wizard who lives in a tower constructed entirely from regular expressions and insists that every problem can be solved elegantly with a one-liner.

Java is the city clerk who requires Form 27-B, submitted in triplicate, before allowing the one-liner anywhere near production.

Between them, they accidentally form a balanced individual.

Perl excels at text processing, scripting, automation, and expressing complex ideas in alarming brevity.

Java excels at portability, stability, tooling, and continuing to execute code long after everyone involved has forgotten why it was written.

PerlOnJava allows each language to exploit the other's strengths while outsourcing its weaknesses.

Like many successful partnerships, it began with mutual suspicion.

---

# Features

## One JAR

One file.

Contained within it are:

* a Perl compiler,
* a Perl runtime,
* standard libraries,
* assorted modules,
* and a quantity of optimism that some experts would describe as "operationally significant."

You may distribute this JAR.

You may archive it.

You may place it reverently upon a shelf.

You may throw it at a system administrator.

The authors recommend against the final option, although they acknowledge that circumstances vary.

---

## Bundled Modules

Useful modules are included.

This decision was reached after extensive scientific investigation determined that installing dependencies individually builds character mainly in the people who aren't doing it.

CPAN remains available for those who enjoy adventure.

---

## Database Access via DBI and JDBC

PerlOnJava speaks to databases through JDBC.

Ancient databases.

Modern databases.

Databases created during mergers that nobody entirely remembers authorizing.

Databases containing a table named `TEMP_FINAL_V2_OLD_BACKUP_DO_NOT_DELETE`.

Especially those databases.

---

## JSR-223 Embedding

Perl can run inside Java applications.

Java developers encountering this feature typically pass through five emotional stages:

1. Curiosity.
2. Denial.
3. Experimentation.
4. Unexpected productivity.
5. The uncomfortable realization that they quite like Perl.

---

## HTTP via Netty

Sometimes a Perl script should become a web service.

The universe provides no guidance on this matter.

PerlOnJava therefore assumes the answer is yes and supplies a fast, non-blocking HTTP stack.

Whether this represents progress is left as an exercise for future historians.

---

## Regular Expressions

Perl regular expressions are among humanity's great achievements.

Alongside cathedrals, orbital mechanics, and inventing cheese.

They have also been compared unfavorably to eldritch incantations.

Both views contain elements of truth.

PerlOnJava preserves Perl's regex behaviour faithfully.

Even the expression you wrote at two in the morning.

Especially that one.

You may not understand it.

But somewhere, deep in its nested parentheses, it understands you.

---

# Getting Started

```bash
java -jar perlonjava.jar myscript.pl
```

The JAR performs the necessary rituals.

It parses Perl source code.

Constructs abstract syntax trees.

Generates JVM bytecode.

Loads classes.

Executes the result.

At no point does it recommend rewriting everything in Kotlin.

---

# Compatibility

PerlOnJava aims for broad compatibility with Perl.

The phrase "broad compatibility" acknowledges two important facts.

First, Perl has existed since 1987.

Second, Perl programmers have spent much of that time discovering entirely new interpretations of the phrase "perfectly reasonable behaviour."

PerlOnJava supports the overwhelming majority of real-world Perl code and continues to improve.

Occasionally, compatibility bugs are discovered.

These are generally distinguished from language features by whether anyone admits to creating them.

---

# Contributing

PerlOnJava is open source.

In principle, it belongs to everyone.

In practice, it belongs to whoever is currently investigating why a test named `t/op/magic/something_really_important.t` has suddenly begun failing on Tuesdays.

Bug reports are welcome.

Pull requests are welcome.

Documentation improvements are welcome.

If you discover behaviour that should not work but demonstrably does, please submit a pull request accompanied by a detailed explanation.

The explanation will be studied carefully.

Future generations deserve answers.

---

# Licence

See `LICENSE`.

The licence document is substantially more authoritative than this one.

It is also less amusing, although this is generally considered appropriate in legal literature.

---

# Final Remarks

PerlOnJava exists because software engineering occasionally advances through careful planning, rigorous analysis, and disciplined execution.

And occasionally because someone says:

> "That sounds ridiculous. I wonder if it works."

PerlOnJava is dedicated to exploring the overlap between these two approaches.

Bring Perl to the JVM.

The JVM has had plenty of time to prepare.

