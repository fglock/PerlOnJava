# Documentation Improvement Plan

## Overview

Thorough review of all top-level `.md` files conducted April 2025. The documentation suite is well-structured and above average for an open-source project. The issues below are refinements, not fundamental problems.

All 58 local links verified via `make check-links` (lychee) — no broken links.

## Table of Contents

- [High Priority](#high-priority)
- [Medium Priority](#medium-priority)
- [Low Priority](#low-priority)
- [Progress Tracking](#progress-tracking)

---

## High Priority

### H1. Remove stale branch regression tracking from AGENTS.md

**File:** `AGENTS.md`

The "Regression Tracking (feature/defer-blocks branch)" section contains detailed regression tables and fix lists specific to a past feature branch. This clutters the file for future sessions and may cause confusion about current project state. Should be archived or removed.

### H2. Reconcile `make` command descriptions across all files

**Files:** `README.md`, `QUICKSTART.md`, `CONTRIBUTING.md`, `AGENTS.md`

The same commands are described differently:

| Command | README | QUICKSTART | CONTRIBUTING | AGENTS.md |
|---------|--------|------------|--------------|-----------|
| `make` | "Build + run all unit tests" | "compiles the project and runs the fast unit tests" | (not described separately) | "Build + run all unit tests" |
| `make dev` | — | — | "Force clean rebuild" | "Build only, skip tests" |

AGENTS.md has the authoritative table. Other files should match it or link to it.

### H3. Extend `make check-links` to cover top-level `.md` files

**File:** `Makefile`

The current target runs `lychee --offline docs/ dev/design/` — it does not check top-level files (`README.md`, `CONTRIBUTING.md`, `SECURITY.md`, etc.). Should add `*.md` at the project root.

### H4. Fix contradictory "Make optional" vs "never use raw gradlew"

**Files:** `QUICKSTART.md`, `AGENTS.md`

QUICKSTART line 9 says "Make (optional - can use Gradle directly)" but AGENTS.md says "NEVER use raw mvn/gradlew commands." For end users just running scripts, Gradle may be fine, but contributors must use `make`. The QUICKSTART qualifier should clarify this distinction.

---

## Medium Priority

### M1. Clarify "Perl 5.42+ features" wording in README

**File:** `README.md`

A first-time user might read "Perl 5.42+ features" as "requires Perl 5.42 installed." Reword to "Perl 5.42 language compatibility" or "Supports Perl 5.42+ language features."

### M2. Align CONTRIBUTING commit/PR workflow with AGENTS.md rules

**File:** `CONTRIBUTING.md`

- "Before committing" checklist says `make test-all` but AGENTS.md says just `make`.
- No mention of "never push to master" or feature branch requirement.
- Should match the AGENTS.md git workflow section.

### M3. Add project independence note to README

**File:** `README.md`

SECURITY.md now clarifies the Perl 5 relationship, but README doesn't mention it. A first-time user could assume PerlOnJava is part of the Perl project. Add a one-liner, e.g., "PerlOnJava is an independent project, not part of the Perl core distribution."

### M4. Link AGENTS.md from CONTRIBUTING.md

**File:** `CONTRIBUTING.md`

AGENTS.md contains valuable testing commands, git workflow rules, and unimplemented feature lists that any contributor would benefit from. Currently not linked from any user-facing doc.

---

## Low Priority

### L1. Add SECURITY.md to README About line

**File:** `README.md`

README links to AI_POLICY.md in the About line but not SECURITY.md. Should add it for discoverability.

### L2. Note AI_POLICY stats are point-in-time

**File:** `AI_POLICY.md`

"As of April 2025, approximately 18% of PerlOnJava commits (~2,250 out of ~12,400)" will go stale. Add a note that these are approximate at time of writing, or a command to regenerate.

### L3. LICENSE.md formatting and copyright year

**File:** `LICENSE.md`

- Links use `[Artistic](Artistic)` — could be `[Artistic License](Artistic)` for clarity.
- No year range: "Copyright (c) Flavio Glock" — convention is "Copyright (c) 2023-2025 Flavio Glock."

### L4. Clarify MILESTONES.md purpose or remove

**File:** `MILESTONES.md`

This 21-line file is a redirect stub pointing to Roadmap and Changelog. If it exists for backward compatibility, it should say so. Otherwise consider removing.

### L5. Add "What is this?" sentence for non-Perl users in README

**File:** `README.md`

The tagline assumes the reader knows Perl and why running it on the JVM matters. A one-sentence value proposition before Features would help (e.g., "Run existing Perl scripts on any platform with a JVM, with access to Java libraries").

### L6. README license line links to raw filenames

**File:** `README.md`

Line 54 links to `Artistic` and `Copying` as raw filenames. Could link to `LICENSE.md` alone for simplicity.

### L7. Add Java version to security recommendations

**File:** `SECURITY.md`

Recommends "keep PerlOnJava updated" but doesn't mention keeping Java updated, which is arguably more important for JVM security.

### L8. Rename AI_POLICY.md "Open Questions" section

**File:** `AI_POLICY.md`

"Open Questions" reads as internal notes. Consider renaming to "Future Considerations" for a top-level document.

---

## Progress Tracking

### Current Status: All fixes applied (2025-04-09)

### Completed
- [x] Documentation review (2025-04-09)
- [x] AI_POLICY.md created and linked
- [x] SECURITY.md — Perl 5 independence clarification added
- [x] Cross-links between AI_POLICY.md and SECURITY.md added
- [x] H1 — Removed stale branch regression tracking from AGENTS.md
- [x] H2 — Reconciled `make` command descriptions across all files
- [x] H3 — Extended `make check-links` to cover top-level `.md` files
- [x] H4 — Fixed contradictory "Make optional" in QUICKSTART.md
- [x] M1 — Clarified "Perl 5.42+ features" wording in README
- [x] M2 — Aligned CONTRIBUTING commit/PR workflow with AGENTS.md rules
- [x] M3 — Added project independence note to README
- [x] M4 — Linked AGENTS.md from CONTRIBUTING.md
- [x] L1 — Added SECURITY.md to README About line
- [x] L2 — Noted AI_POLICY stats are point-in-time with regeneration command
- [x] L3 — Improved LICENSE.md link text clarity
- [x] L5 — Added value proposition sentence for non-Perl users in README
- [x] L6 — Simplified README license line
- [x] L7 — Added JVM update recommendation to SECURITY.md
- [x] L8 — Renamed "Open Questions" to "Future Considerations" in AI_POLICY.md

### Not Applied (deferred)
- L4 — MILESTONES.md: left as-is (likely exists for backward compatibility)
