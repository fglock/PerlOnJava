# Issue organization and agent coordination

Status: minimal workflow implemented for
[#1895](https://github.com/fglock/PerlOnJava/issues/1895), 2026-10-27; review and
merge pending. The adopted rules are in the
[triage guide](../../docs/guides/issue-triage.md); the dated
[planning index](issue-queue.md) separates organizational and project work.
This supersedes the broader [#1593](https://github.com/fglock/PerlOnJava/issues/1593)
rollout rather than claiming that rollout was completed.

## Decisions

- Classify indexed work by requested outcome: organizational planning and
  qualification versus project behavior, features and executable tooling.
  Keep technical areas independent of this distinction.
- Use the checked-in index for reviewed selection, priority rationale and
  dependencies. Its states are a snapshot; current issue claims and PRs are the
  execution record. Refresh candidate state before starting work.
- Use temporary agent/session claims, not mandatory assignees or permanent
  subsystem owners. A marked issue comment is the stable current checkpoint.
  Comments are advisory and cannot guarantee exclusive assignment; resolve races
  before overlapping mutations and use separate worktrees.
- Existing file-coordinated pools retain coordinator/lease/fencing authority.
  Link to those records instead of creating a second scheduler. An old timestamp
  alone does not authorize takeover.
- Keep one current checkpoint, normally 200–400 words, with source/work identity,
  results, evidence, blockers and next action. Replace repeated narrative with
  links to full evidence. Preserve immutable event journals and maintain a
  separate summary/cursor so resumption does not require a full replay.
- Group issues only for shared code/tests or a finite delivery dependency.
  Groups route work; they do not establish shared root cause, priority, blocking
  relationships or permission to combine fixes.

## Evidence and limits

The 2026-10-01 audit counted 162 open issues, seven without labels and 37 without
areas. On 2026-10-27 there were 68 open issues in the refreshed implementation
snapshot, including #1895; one lacked labels and an area. These are dated
observations, not ongoing guarantees. #1891 closed while planning was being
reviewed, illustrating why old shortlists must be refreshed.

Read-only issue bodies, current PR metadata and the #1649 phase contract informed
the index. No fresh runtime reproduction was performed for this documentation
change. The index distinguishes reported evidence, active candidates and pending
verification. In particular, passing checks reported by PR #1816 do not prove
that the complete ownership project has shipped.

The existing GitHub labels remain sufficient. Project configuration could not
be inspected because the credential lacks `read:project`; the minimal workflow
works without that access. No Project, new labels, forms, scheduler or automated
classification is introduced. Release milestones and recurring triage remain
optional proposals tied to an actual need.

## Progress tracking

| Phase | Result | Evidence |
|---|---|---|
| Audit and reporting guide, 2026-10-01 | Completed | Contributor/skill links and reporting guidance already existed |
| Minimal planning and coordination, 2026-10-27 | Implemented; merge pending | Updated AGENTS.md, triage guide and planning index; current claims, dependency holds and concise checkpoint protocol |
| Skill alignment, 2026-10-27 | Implemented; merge pending | Shortened generic debugging, ExifTool, parity, porting and mailbox guidance; retained technical invariants and updated triage links |

Implementation branch: `docs/issue-1895-agent-coordination`, based on
`a698bb422`. Exact source/PR and validation are recorded in the implementing PR
and the [current claim](https://github.com/fglock/PerlOnJava/issues/1895#issuecomment-6437578415).
All 15 skills pass frontmatter validation; repository and skill offline links
pass. At merge, record that merged source reference here.

### Next steps

1. Review and merge #1895's documentation change; retain the link-check and skill
   validation evidence in its PR.
2. Agents selecting work refresh their candidate issue, PRs and claims, then
   update only affected queue rows and checkpoints.
3. When #1649 delivers a required default ownership path, verify that path's
   issue-specific acceptance before releasing the corresponding hold.

### Open questions

- Suspected cleanup overlap for #1621 is not yet a proven #1649 prerequisite;
  reduce and reconcile it with the active ownership work before scheduling a
  competing implementation.
- #1114 and #1278 overlap in updater/manifest scope. Agree their delivery boundary
  before creating independent implementations.

## Related guidance

- [Agent guidelines](../../AGENTS.md#agent-claims-and-concise-checkpoints)
- [Mailbox coordination](../../.agents/skills/file-mailbox-coordination/SKILL.md)
- [CPAN prioritization](cpan-port-prioritization.md)
