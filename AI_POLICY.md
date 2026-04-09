# AI-Assisted Development Policy

PerlOnJava uses AI coding assistants as part of its development workflow. This document describes our policy for AI-assisted contributions and the research behind it.

## Table of Contents

### Part 1 — Policy

- [Commit Attribution](#commit-attribution)
- [Human Responsibility](#human-responsibility)
- [What Counts as AI-Assisted](#what-counts-as-ai-assisted)
- [Future Considerations](#future-considerations)

### Part 2 — Research

- [Industry Landscape](#industry-landscape)
  - [Outright Ban — Gentoo Linux](#1-outright-ban--gentoo-linux)
  - [Mandatory Attribution — Linux Kernel](#2-mandatory-attribution--linux-kernel)
  - [Guidelines with Recommended Token — Apache Software Foundation](#3-guidelines-with-recommended-token--apache-software-foundation)
  - [PR-Level Disclosure — Oh My Zsh](#4-pr-level-disclosure--oh-my-zsh)
  - [Copyleft and Training Data Concerns — Software Freedom Conservancy](#5-copyleft-and-training-data-concerns--software-freedom-conservancy)
- [Comparison of Attribution Mechanisms](#comparison-of-attribution-mechanisms)
- [Key Observations](#key-observations)

---

# Part 1 — Policy

## Commit Attribution

AI-assisted commits include two markers in the commit message:

```
Generated with [TOOL_NAME](TOOL_DOCS_URL)

Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>
```

Replace `TOOL_NAME` with the AI tool's name (e.g. Devin, Copilot, Claude), `TOOL_DOCS_URL` with a link to its documentation, and `TOOL_BOT_EMAIL` with the tool's GitHub bot email address (e.g. `158243242+devin-ai-integration[bot]@users.noreply.github.com`).

- **`Generated with`** — identifies the tool and links to its documentation.
- **`Co-Authored-By`** — a GitHub-recognized trailer that shows the AI tool as a co-author in the commit UI. GitHub renders this as a visible avatar and link on the commit.

As of April 2025, approximately 18% of PerlOnJava commits (~2,250 out of ~12,400) carry a Devin `Co-Authored-By` tag. (These numbers are approximate at time of writing; query with `git log --all --format="%B" | grep -c "Co-[Aa]uthored-[Bb]y.*[Dd]evin"`.)

## Human Responsibility

The human committer reviews all AI-generated code before committing and takes full responsibility for correctness, licensing, and adherence to project standards. AI-assisted commits go through the same PR review process as any other contribution.

Reviewers of AI-assisted contributions should pay particular attention to security-sensitive areas such as `eval`, `system`/`exec`, and Java integration surfaces. See [SECURITY.md](SECURITY.md) for PerlOnJava's security considerations and the specific constructs that require care.

## What Counts as AI-Assisted

A commit is tagged as AI-assisted when an AI tool (Devin, Copilot, Claude, etc.) generated or substantially contributed to the code, commit message, or design in that commit. Minor uses such as autocomplete suggestions or spell-checking do not require attribution.

## Future Considerations

- Define whether PerlOnJava should adopt a more structured trailer (e.g., `Assisted-by:`) in addition to or instead of `Co-Authored-By`.
- Consider whether model version should be recorded.
- Review implications for PerlOnJava's license (Artistic License 2.0 / GPL) regarding AI-generated code.

---

# Part 2 — Research

Research conducted in April 2025 across major open-source projects reveals a spectrum of approaches, from outright bans to permissive use with attribution.

## Industry Landscape

### 1. Outright Ban — Gentoo Linux

**Source:** [LWN.net, April 2024](https://lwn.net/Articles/970072/)

The Gentoo Council voted unanimously to ban AI-generated contributions:

> "It is expressly forbidden to contribute to Gentoo any content that has been created with the assistance of Natural Language Processing artificial intelligence tools."

Concerns cited: copyright risk from training data, quality control, and ethics. Enforcement is trust-based; the project acknowledges it cannot detect AI-generated code but sets clear expectations.

### 2. Mandatory Attribution — Linux Kernel

**Source:** [kernel.org `Documentation/process/coding-assistants.rst`](https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/plain/Documentation/process/coding-assistants.rst)

The Linux kernel requires:

- **AI agents MUST NOT add `Signed-off-by` tags.** Only humans can certify the Developer Certificate of Origin (DCO).
- **An `Assisted-by:` trailer is required**, in a machine-parseable format:
  ```
  Assisted-by: Claude:claude-3-opus coccinelle sparse
  ```
  Format: `AGENT_NAME:MODEL_VERSION [TOOL1] [TOOL2]`

This uses git's trailer system (same as `Signed-off-by`, `Reviewed-by`, `Fixes:`), making it searchable and extractable from git history.

### 3. Guidelines with Recommended Token — Apache Software Foundation

**Source:** [apache.org — ASF Generative Tooling Guidance](https://www.apache.org/legal/generative-tooling.html)

The ASF permits AI-generated code if contributors ensure:

1. The AI tool's terms do not conflict with the Open Source Definition.
2. No third-party copyrighted materials are included, or any such materials are compatibly licensed.
3. Contributors obtain reasonable certainty via tools that flag training-data-similar output.

For attribution, the ASF recommends a `Generated-by:` token in commit messages, designed to be automatically extracted into a machine-readable Tooling-Provenance file for releases.

### 4. PR-Level Disclosure — Oh My Zsh

**Source:** [ohmyzsh PR template](https://github.com/ohmyzsh/ohmyzsh/blob/master/.github/PULL_REQUEST_TEMPLATE.md)

Their PR checklist includes:

> "If I used AI tools (ChatGPT, Claude, Gemini, etc.) to assist with this contribution, I've disclosed it below."

Disclosure is at the PR level rather than per-commit.

### 5. Copyleft and Training Data Concerns — Software Freedom Conservancy

**Source:** [sfconservancy.org, February 2022](https://sfconservancy.org/blog/2022/feb/03/github-copilot-copyleft-gpl/)

The SFC raised concerns about GitHub Copilot being trained on copyleft-licensed code and potentially reproducing it without license compliance. This underpins many projects' caution about AI-generated contributions.

## Comparison of Attribution Mechanisms

| Mechanism | Used by | Machine-parseable | Granularity | Notes |
|-----------|---------|-------------------|-------------|-------|
| `Assisted-by:` trailer | Linux kernel | Yes | Per-commit | Includes model version and tools |
| `Generated-by:` token | Apache (recommended) | Yes | Per-commit | Designed for release provenance files |
| `Co-Authored-By:` + `Generated with` | PerlOnJava, Devin | Yes | Per-commit | GitHub renders co-author in UI |
| PR template checkbox | Oh My Zsh | No | Per-PR | Contributor self-disclosure |
| Outright ban | Gentoo | N/A | N/A | Trust-based enforcement |

## Key Observations

1. **No project has solved detection.** Gentoo, the kernel, and others all acknowledge that enforcement is trust-based. Policies set expectations and enable traceability rather than policing.

2. **`Co-Authored-By` vs `Assisted-by`**: `Co-Authored-By` implies shared authorship and responsibility. The kernel's `Assisted-by` makes it explicit that the human takes full responsibility and the AI was a tool. This distinction matters for DCO/legal purposes in projects that require sign-off.

3. **Model versioning**: The kernel's format captures which model version was used, which is useful for auditing if a model is later found to have training-data issues.

4. **The trend is toward transparency, not prohibition.** Most active projects (outside Gentoo) accept AI-assisted contributions but require clear attribution.
