# Presentations

Conference talks, workshop materials, and blog posts about PerlOnJava.

## Purpose

This directory stores source files for external communications: slide decks,
blog-post drafts, and supporting examples used in presentations. These are
not part of the software distribution but are kept in the repository for
reproducibility and version-tracking.

## Contents

| Directory | Event / Venue | Description |
|-----------|---------------|-------------|
| [German_Perl_Raku_Workshop_2026/](German_Perl_Raku_Workshop_2026/) | German Perl/Raku Workshop 2026 | Slide deck (Markdown + HTML), presentation plan, speaker notes |
| [blogs_perl_org_jcpan_2026/](blogs_perl_org_jcpan_2026/) | blogs.perl.org — jcpan 2026 | Blog post drafts, benchmark results, example scripts |

## Adding New Presentations

1. Create a subdirectory named `<Venue_Year>/` (underscores for spaces).
2. Include a `README.md` inside that explains the event, date, and abstract.
3. Put slide sources, build scripts, and supporting code in the same directory.
4. Do not commit rendered binaries (PDFs, large images) unless they are small
   and essential.

## See Also

- `dev/design/perl-summit.md` — Notes from the Perl Summit discussion
- `dev/design/docs.md` — Documentation strategy
