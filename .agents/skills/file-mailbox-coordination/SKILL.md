---
name: file-mailbox-coordination
description: Coordinate local agents through an append-only file mailbox when no native messaging is available, including assignment, resumable summaries, leases, immutable gates, shared-load limits and safe recovery.
---

# File Mailbox Coordination

Use shared files as an asynchronous control plane for coding agents. Treat the
mailbox as an authoritative event journal, not as informal notes.

When coordinating repository build gates, run `make` with high niceness (low
CPU priority) so parallel development on the same host is not disrupted:

```bash
nice -n 19 make <target>
```

## Establish the mailbox

Resolve the mailbox path from the user's prompt or the current task context. Do
not guess between multiple plausible mailboxes. Keep the mailbox outside Git
worktrees so agents can coordinate without creating repository changes or merge
conflicts.

Support either layout:

1. An existing append-only handoff file such as `/tmp/regex-implementation-handoff.md`.
2. A mailbox directory with one immutable message per file. Prefer this layout
   when starting a new pool:

```text
<mailbox>/
├── COORDINATOR.md
├── STATUS.md
├── agents/
├── messages/new/
├── messages/acknowledged/
├── messages/completed/
├── tasks/
├── heartbeats/
└── artifacts/
```

Preserve every message. Never rewrite or delete another agent's entry. For a
single handoff file, append timestamped sections at the physical end of the
file and verify the resulting tail. “Append-only” is about byte position, not
merely preserving old text: inserting a message above EOF is invisible to a
worker whose `tail -F` cursor already passed that position.

Before every write, read the current final unique lines and use them as the
patch anchor. After the write, read the tail again and require the new message
to be the final section. If repeated patch context inserted it earlier,
immediately append one consolidated superseding message at physical EOF; do
not assume the watcher saw the misplaced copy. Prefer a unique current-EOF
message identifier over generic state fields as the append anchor.
For a mailbox directory, create immutable uniquely named message files and use
atomic rename or creation for state transitions.

Read the repository's `AGENTS.md` before inspecting or touching any checkout.
Obey its dirty-tree, Git, testing, timeout, and process-cleanup rules. Give each
worker a separate worktree and branch. Exchange commit SHAs, patches, log paths,
and artifact checksums instead of letting agents edit one worktree concurrently.

## Join and exchange messages

Read [roles and message schemas](references/roles-and-messages.md) when joining
or assigning work. The first agent atomically claims coordinator; later agents
register as workers. Never infer an assignment from unfinished work. A stale
coordinator requires authorized takeover, not self-promotion. Every assignment
and result carries coordinator term, attempt and lease token; ignore old terms
and fence expired attempts.

## Coordinate tasks

Make the coordinator:

- maintain the dependency graph and current authoritative state;
- issue assignments with exact inputs and authority boundaries;
- serialize overlapping file ownership and integration decisions;
- keep integration review, conflict resolution, authoritative-state updates,
  regression triage, and publication on the coordinator's serial critical path;
- delegate independent long read-only gates when immutable private inputs and
  the published resource limit permit parallel execution;
- declare bounded shared-resource limits and require workers to self-monitor the
  global active count immediately before starting expensive work;
- validate worker evidence before accepting completion;
- fence expired attempts and reassign recoverable work;
- publish status summaries without erasing the event history.

Make each worker:

1. verify its checkout, branch, SHA, and dirty-tree state;
2. acknowledge and atomically claim the assigned task;
3. work only within the assignment and owned worktree;
4. report questions before making scope-changing decisions;
5. capture complete test output and exact command outcomes;
6. publish commits, logs, artifacts, and remaining risks;
7. release leases and return to `AVAILABLE` or `OFFLINE`.

Use task states:

```text
READY -> CLAIMED -> WORKING -> BLOCKED -> COMPLETED
                     |                     |
                     +-> STALE ------------+
```

Never report completion until required validation and externally observable
state agree with the claim.

### Use bounded execution envelopes

Authorize a cohesive outcome in one assignment instead of requiring a new
coordinator ping after each diagnosis, focused test, build, commit, or push.
Record the exact base, worktree, branch, lease, owned and excluded files,
ordered actions, required gates and logs, resource policy, allowed correction
budget, stop conditions, and post-delivery state. Size the lease around the
cohesive outcome, not one assertion or routine command; renew it during long
gates.

A dependency handoff may self-advance only after the worker verifies the
reported SHA, file scope, and validation against the assignment. Bounded
authority never pre-authorizes scope growth, destructive recovery, merges,
force-pushes, or unrelated external writes.

### Scope commands to new worktrees

`git worktree add` does not change the caller's working directory. Scope every
subsequent Git command with `git -C /exact/new/worktree` and give every build or
test that worktree as its explicit cwd. Verify both checkouts before and after
the first mutation. If a command lands in the wrong checkout, stop and preserve
it under the repository's dirty-tree recovery rules.

## Keep monitoring alive

Remain in monitoring mode until explicitly released, replaced, or authorized to
stop. Poll after 1 minute, then 2 minutes, then every 5 minutes while idle. Add
up to 15 seconds of jitter. Reset to 1 minute whenever relevant activity occurs.

Poll additionally:

- before claiming a task or starting an expensive shared-resource operation;
- before starting an expensive build or corpus run;
- after every long-running command;
- before commit, push, rebase, PR publication, or merge;
- immediately after completing or blocking a task.

Prefer a lightweight external watcher that checks file metadata and resumes the
agent only when content changes. An instruction cannot wake an agent after its
process exits. If the environment lacks recurring monitoring, keep a supervisor
or native scheduled continuation active. Do not claim to be monitoring when no
wake mechanism exists.

Avoid one opaque sleep or wait longer than the environment permits. Preserve the
1/2/5-minute schedule through a recurring monitor or several bounded waits.

## Gates, leases and safe exit

Before shared gate/lease work, read
[gates and recovery](references/gates-and-recovery.md). It defines immutable
private executable inputs, GATE_STARTED/GATE_FINISHED evidence, 15-minute
heartbeats, renewable leases, shared-load launch fencing, crash recovery,
coordinator transfer and draining. Preserve those safety and recovery rules.
Use only the sections relevant to the current transition rather than repeatedly
loading the whole protocol. Never infer that a failed process inspection proves
absence, revoke ownership from a quiet log alone, or kill another valid worker's
process to reclaim capacity.

## Keep resumable state small

Apply [AGENTS.md's checkpoint rule](../../../AGENTS.md#agent-claims-and-concise-checkpoints).
The coordinator maintains STATUS.md as a short current summary with coordinator
term, active tasks/leases, source/artifact references, blockers, next actions and
last incorporated message ID. It atomically replaces its own summary after a
material transition; workers keep separate current summaries. Preserve messages
and full logs in the journal/artifacts, and link details rather than copying
transcripts. Protocol-required heartbeats remain small structured records.

On resume, verify the summary against authoritative term/lease records and read
only messages since its cursor plus referenced context. Use a targeted journal
replay when evidence is incomplete or inconsistent. Summary state never
overrides a newer lease revocation or assignment. Do not truncate an append-only
file, erase another agent's message, or rotate a journal out from under readers.

Link the task's issue claim to this pool record when GitHub is in use; pool
leases remain authoritative rather than competing with advisory issue comments.
