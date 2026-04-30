# Memory Management in PerlOnJava

PerlOnJava lives inside the JVM, so the underlying memory manager is always
the JVM's [tracing garbage collector][tracing-gc]. Perl 5, however, expects
two semantics that a tracing GC alone cannot deliver:

- **`DESTROY`** — destructor methods that fire deterministically when an
  object becomes unreachable, in a predictable order, before the program
  observes any subsequent statement;
- **`Scalar::Util::weaken`** — weak references that are nulled out at the
  moment the referent's last strong reference disappears.

To implement these on top of a tracing GC, PerlOnJava layers a small
**selective reference-counting overlay** on top of the JVM heap. Most
allocations remain pure JVM objects with zero bookkeeping; only the small
subset of objects that actually need deterministic destruction or weak-ref
support carry refcount metadata. Everything else — cycles, large object
graphs, transient closures — is left to the JVM's regular garbage collector.

The full implementation is documented in
[`dev/architecture/weaken-destroy.md`][weaken-destroy] (lifecycle examples,
state machine, all components, edge cases, and limitations). This page is
the user-facing summary and the literature context.

## How the overlay works

| Concern | Mechanism |
|---|---|
| Which objects carry refcounts | Blessed into a class with a `DESTROY` method, anonymous containers (`{}`, `[]` when birth-tracked), closures with captures, and the targets of `weaken()`. Unblessed scalars / unblessed plain data carry no refcount and are managed solely by the JVM GC. |
| Increment / decrement | Hooks at every reference-assignment site (`setLarge`), container-store (`incrementRefCountForContainerStore`), and scope exit (`scopeExitCleanup`) for tracked referents only — short-circuited for the untracked majority. |
| Statement boundary cleanup | A `MortalList` (the analogue of Perl 5's `FREETMPS` / mortal stack) defers decrements to a flush point so temporaries survive their use. |
| Weak references | Tracked in `WeakRefRegistry` (forward set + reverse identity map). When a tracked referent's count reaches zero, all weak refs to it are cleared to `undef` and `DESTROY` runs. |
| Cycle handling | The JVM tracing GC reclaims cycles as usual; refcount alone cannot. Where Perl programs use `weaken()` to break cycles for *DESTROY-timing* reasons, the overlay honors that. |
| Reachability reconciliation | A `ReachabilityWalker` walks Perl roots when refcount drifts from real reachability — most often during deferred-decrement flushes for blessed objects stored as values of package-global hashes (e.g. `our %METAS = ...`). It rescues objects that the walker proves are still reachable, preventing premature `DESTROY`. |

This is a deliberately *narrow* refcount: only what's needed to honour
Perl's `DESTROY` and `weaken` contracts, and not the full Perl 5
reference-counting discipline. In particular, plain scalars, plain
containers, and unblessed data carry no refcount at all — those code
paths cost the same as untracked Java objects.

## Where this fits in the GC literature

Combining reference counting with a tracing garbage collector is a
well-studied design. The standard reference is *The Garbage Collection
Handbook* by Jones, Hosking & Moss (CRC Press, 2nd ed. 2023):

- [www.gchandbook.org][gc-handbook] — book home page with chapter list and
  bibliography.

Two foundational papers describe the design space:

- **David F. Bacon, Perry Cheng, and V. T. Rajan**, *"A unified theory of
  garbage collection"*, OOPSLA 2004
  ([DOI: 10.1145/1028976.1028982][bacon-2004]). Frames tracing and
  reference counting as duals of the same algorithm and characterizes
  hybrid collectors as points on a spectrum between them.
- **Stephen M. Blackburn and Kathryn S. McKinley**, *"Ulterior reference
  counting: fast garbage collection without a long wait"*, OOPSLA 2003
  ([author PDF][urc-paper] · [DOI: 10.1145/949305.949336][urc-doi]).
  Pioneered partitioning the heap so that one part is reference-counted
  and another is tracing-collected, with each managing the other's
  inter-partition references.

Wikipedia provides the encyclopaedic introductions:

- [Reference counting][wiki-rc]
- [Tracing garbage collection][wiki-tracing]
- [Garbage collection (computer science)][wiki-gc]
- [Finalizer][wiki-finalizer] — discusses the deterministic-destructor /
  non-deterministic-finalizer distinction that motivates PerlOnJava's
  overlay in the first place.

### How PerlOnJava fits the spectrum

In the Bacon/Cheng/Rajan framing, a hybrid collector partitions the heap
and applies tracing to one part and reference counting to the other. The
Blackburn/McKinley *ulterior* design picks the partition along a
generational boundary (mature vs. nursery). PerlOnJava's overlay picks
the partition along a **per-class behavioural boundary**: *"does this
class need finalization or weak-reference semantics that the JVM cannot
already provide?"* If yes, the object is added to the refcounted side at
`bless` time; if no, it stays on the pure tracing side forever. The
refcount is then strictly local — it exists only to schedule `DESTROY`
calls and to clear weak references at the right moment, while the JVM's
tracing GC remains the actual memory manager.

This is also why we describe the overlay as **selective** rather than
*deferred* or *ulterior*: the partition criterion is neither a write-barrier
optimisation nor a generational boundary, but the runtime question of
whether deterministic finalization is required for this object's class.

## Comparison with other Perl runtimes and the JVM

| | Perl 5 (perl) | JVM finalization | PerlOnJava |
|---|---|---|---|
| Primary GC | Reference counting + cycle collector | Tracing | Tracing (JVM) |
| `DESTROY` timing | Deterministic, immediate at refcount 0 | Non-deterministic; `Object.finalize` is deprecated | Deterministic for tracked classes; matches Perl 5 timing for the cases users observe |
| Cycles | Leak unless broken with `weaken` | Reclaimed by tracing GC | Reclaimed by tracing GC; `weaken` still needed for `DESTROY` *timing* |
| Weak refs | `Scalar::Util::weaken` (built into refcount) | [`java.lang.ref.WeakReference`][java-ref] / `PhantomReference` / `Cleaner` | `Scalar::Util::weaken` implemented via `WeakRefRegistry` and refcount hooks |
| Cost when feature unused | Per-op refcount on every SV | Zero | Zero — `refCount == -1` short-circuit on every untracked object |

For Perl-language semantics, see also `perlobj`'s
[Destructors][perl-destructors] section.

## Limitations

The full set of edge cases lives in
[`dev/architecture/weaken-destroy.md`][weaken-destroy]. The user-visible
ones are:

- `DESTROY` is delivered for tracked objects (those blessed into a class
  with `DESTROY`) at the same moment Perl 5 would deliver it; for
  untracked objects (e.g. unblessed data with weak refs) it relies on JVM
  GC and timing is approximate.
- `Internals::SvREFCNT($ref)` returns an approximate count rather than
  the raw value Perl 5 would report — useful for `weaken`/`DESTROY`
  invariants, not for byte-for-byte refcount fidelity.
- `fork` and Perl-level `threads` are not supported by the JVM backend;
  the refcount overlay is single-threaded.
- `local($@, $!, $?)` around `DESTROY`: only `$@` is currently saved and
  restored.

[tracing-gc]: https://en.wikipedia.org/wiki/Tracing_garbage_collection
[weaken-destroy]: ../../dev/architecture/weaken-destroy.md
[gc-handbook]: https://www.gchandbook.org/
[bacon-2004]: https://doi.org/10.1145/1028976.1028982
[urc-paper]: https://users.cecs.anu.edu.au/~steveb/pubs/papers/urc-oopsla-2003.pdf
[urc-doi]: https://doi.org/10.1145/949305.949336
[wiki-rc]: https://en.wikipedia.org/wiki/Reference_counting
[wiki-tracing]: https://en.wikipedia.org/wiki/Tracing_garbage_collection
[wiki-gc]: https://en.wikipedia.org/wiki/Garbage_collection_(computer_science)
[wiki-finalizer]: https://en.wikipedia.org/wiki/Finalizer
[java-ref]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/package-summary.html
[perl-destructors]: https://perldoc.perl.org/perlobj#Destructors
