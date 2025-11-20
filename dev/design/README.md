# PerlOnJava Design Documents

This directory contains design documents, architecture notes, and technical specifications for the PerlOnJava project. Documents are added and updated dynamically as the project evolves.

---

## Purpose

Design documents serve to:
- **Document architectural decisions** before and during implementation
- **Capture complex technical requirements** that need detailed analysis
- **Provide context** for why certain approaches were chosen
- **Guide implementation** with clear specifications
- **Support code reviews** with reference material
- **Help onboarding** by explaining non-obvious design choices

---

## Document Types

### Architecture Documents
High-level system design, component interactions, and overall structure. These explain **how major subsystems work together**.

### Feature Specifications
Detailed designs for specific language features, showing **what needs to be implemented** and how it maps to Perl semantics.

### Implementation Notes
Technical details about **how specific features are implemented**, including edge cases, known issues, and workarounds.

### Analysis & Research
Exploration of different approaches, trade-off analysis, and decisions between alternatives. Shows **why certain paths were chosen**.

### Integration Guides
How PerlOnJava integrates with external systems: JSR-223, web servers, native code, Java libraries.

### Problem Documentation
Deep dives into specific bugs, edge cases, or subtle language behaviors that required careful handling.

---

## Key Topic Areas

The design documents cover:

- **Core Runtime:** Variable system, execution model, memory management
- **Parser & Compiler:** Syntax analysis, code generation, AST transformations
- **Concurrency:** Multiplicity, threads, fork emulation, isolation strategies
- **Language Features:** Regex, operator overloading, tied variables, dynamic scoping
- **System Integration:** JSR-223, web servers, POSIX, native libraries
- **Optimization:** Performance improvements, caching strategies, JIT opportunities
- **I/O & Signals:** File handles, signal handling, terminal control
- **Distribution:** Packaging, versioning, platform support

---

## Finding What You Need

### By Implementation Status

- **Implemented:** Documents describing features that are complete
- **In Progress:** Designs for features currently under development
- **Planned:** Specifications for future work
- **Incubating:** Experimental or exploratory designs

### By Scope

- **Core Architecture:** Fundamental design decisions affecting the whole system
- **Feature-Specific:** Focused on implementing one particular capability
- **Problem-Solving:** Analyzing and fixing specific issues
- **Integration:** Connecting PerlOnJava with external systems

### By Audience

- **New Contributors:** Start with getting started guides and overview documents
- **Feature Implementers:** Read relevant feature specifications and architecture docs
- **Performance Tuners:** Focus on optimization and profiling documents
- **Embedders:** Review JSR-223 and integration guides

---

## Document Lifecycle

1. **Proposal:** Initial design ideas, often sketchy
2. **Draft:** Detailed specification under review
3. **Active:** Guide for current implementation work
4. **Reference:** Completed features, maintained for understanding
5. **Superseded:** Replaced by newer designs, kept for historical context

---

## Contributing Design Documents

When adding new design documents:

- **Use clear titles** that indicate the topic and scope
- **Start with context:** What problem does this solve? Why is this needed?
- **Provide examples:** Show concrete code or usage patterns
- **Discuss alternatives:** What other approaches were considered?
- **Link related docs:** Reference other relevant design documents
- **Keep it current:** Update as implementation reveals new insights

Formats: Markdown (`.md`) for structured documents, text (`.txt`) for quick notes.

---

## Key Resources

For the most important architectural decisions and current work, check:

- **multiplicity.md** - Multiple independent Perl runtimes (enables fork/threads/web concurrency)
- **jsr223-perlonjava-web.md** - JSR-223 compliance and web server integration
- **FORK.md** / **Threads.md** - Concurrency model and limitations

These represent major architectural directions for the project.

---

## Getting Started

**New to PerlOnJava?** Look for documents with "GETTING_STARTED" or "OVERVIEW" in the title.

**Working on a specific feature?** Search for documents matching the feature name or related keywords.

**Planning new work?** Review existing architecture docs first to understand constraints and patterns.

**Questions?** Design documents often include "Open Questions" sections - these are great discussion starting points.

---

_This directory is actively maintained. Documents are added, updated, and occasionally reorganized as the project evolves._
