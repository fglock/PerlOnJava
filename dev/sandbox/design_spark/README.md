# Design Sparks

Early-stage exploratory ideas and conceptual discussions that may eventually
become formal design documents or implementation work.

## Purpose

This directory is for **brainstorming and pre-design thinking** — sketches that
are not yet ready for `dev/design/`. Contents here are typically:

- Speculative integration ideas that need further feasibility study
- Architectural discussions captured before a decision is made
- Proof-of-concept code or pseudocode accompanying an idea
- Notes from conversations about potential directions

Documents here carry **no implementation commitment**. They may be promoted
to `dev/design/`, abandoned, or left here for historical reference.

## Contents

| File | Topic |
|------|-------|
| [SPARK.md](SPARK.md) | PerlOnJava + Apache Spark distributed computing integration design |
| [discussion.md](discussion.md) | General design discussion notes |
| [discussion_cloudpickle.md](discussion_cloudpickle.md) | CloudPickle-style closure serialization exploration |
| [example.pl](example.pl) | Example Perl code for the Spark integration concept |
| [example_noclosure.pl](example_noclosure.pl) | Spark integration without closure serialization |
| [worker.java](worker.java) | Java worker stub for Spark integration sketch |

## See Also

- `dev/design/` — Formal design documents ready to guide implementation
- `dev/sandbox/` — Quick proof-of-concept scripts
