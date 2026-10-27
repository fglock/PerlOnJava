# Pool registration and message schema

Read when joining/electing a coordinator, registering a worker or composing an
assignment. Preserve explicit authority, terms, attempts and lease tokens.

## Join the pool

### Elect the first agent as coordinator

Make the first agent that joins a new pool its coordinator. Use an atomic claim
so two simultaneously starting agents cannot both become coordinator.

1. Inspect the mailbox for an active coordinator declaration and heartbeat.
2. If one exists, join as a worker.
3. If the pool is new, atomically create a sidecar coordinator-lock directory,
   for example `<mailbox>.coordinator-lock` or `<mailbox>/coordinator-lock`.
4. If creation succeeds, become coordinator with term `1`, create the initial
   mailbox state, and append a coordinator registration.
5. If creation fails, reread the coordinator state and join as a worker.

Do not self-promote merely because a coordinator is slow. If an existing
coordinator is stale, require explicit user or supervisor authorization before
moving the old lock aside and claiming a higher term.

Record coordinator registration with:

```yaml
agent_id: perlonjava2-coordinator
role: coordinator
coordinator_term: 1
checkout: /absolute/worktree/path
branch: coordination/phase-name
sha: full-commit-sha
joined_at: 2026-08-18T10:00:00+02:00
state: ACTIVE
last_processed_message: 0
```

Maintain exactly one active coordinator term. Include the term in every
assignment and authorization. Make workers ignore messages from older terms.

### Register later agents as workers

Generate a stable unique agent ID. Read the coordinator's current STATUS.md,
verify its coordinator term and processed-message cursor, then read messages
since that cursor plus any referenced assignment/lease records before
registering. If the summary is missing or inconsistent, reconstruct the
relevant state from the journal once and publish a corrected summary; do not
repeat full-history reads at every poll. Then append:

```yaml
agent_id: perlonjava6-regex-tester
role: worker
coordinator_term: 1
checkout: /absolute/worktree/path
branch: wip/phase-name
sha: full-commit-sha
capabilities:
  - focused JVM tests
  - interpreter parity tests
state: AVAILABLE
authority:
  - run focused tests
  - commit only to the owned branch
restrictions:
  - no full build without checking the declared global concurrency limit
  - no merge, push, or shared-branch rebase without authorization
joined_at: 2026-08-18T10:05:00+02:00
last_processed_message: 12
```

Wait for an explicit assignment. Never infer ownership from visible unfinished
work.

## Exchange messages

Give every message a unique monotonic sequence or sortable unique ID. Include:

```yaml
id: msg-000013
from: perlonjava2-coordinator
to: perlonjava6-regex-tester
coordinator_term: 1
type: assignment
task_id: regex-implementation-break-validation
attempt: 1
lease_token: 01J61F...
reply_to: null
created_at: 2026-08-18T10:06:00+02:00
priority: normal
```

Follow the header with a concise Markdown body containing the observed state,
exact SHA, decision or action requested, allowed mutations, expected evidence,
and completion conditions.

Address messages to an agent ID, `coordinator`, or `broadcast`. Acknowledge every
assignment, authorization, resource claim, and blocking question. Record
`last_processed_message` so agents process each message once. Use idempotency
keys for actions that may be retried.

Do not treat silence as approval. After 10–15 minutes without an expected reply,
send a liveness reminder and continue only read-only checks or already-owned
work.
