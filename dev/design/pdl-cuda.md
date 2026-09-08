# Numerical performance for PerlOnJava: PDL, NumPy, and CUDA

Status: proposed; implementation has not started. Research baseline: 2026-09-08.

## Vision and realistic scope

Make PerlOnJava useful for numerical Perl workloads through either a small,
direct CUDA API or a deliberately bounded PDL-compatible implementation with
optional GPU acceleration, with NumPy data interchange and a possible
NumPy-style Perl array API. PDL compatibility is optional and must not block
useful CUDA support. Perl remains the orchestration language; dense numerical work
runs in bulk Java loops or CUDA libraries and kernels. Both PerlOnJava execution
backends should call the same numerical runtime.

On the PDL route, the first useful result is a small, reliable array toolkit with familiar PDL
construction, arithmetic, broadcasting, reductions, and views. The first GPU
result, on either route, is an explicit device-resident pipeline that transfers inputs once,
performs several operations, and returns a result. Neither result establishes
that arbitrary CPAN PDL applications work unchanged.

| Horizon | Achievable outcome | Boundary |
|---|---|---|
| Feasibility | Pin upstream PDL, reproduce loading failures, specify a supported numerical subset | No compatibility claim from successful loading alone |
| Direct CUDA preview | Typed device buffers, explicit transfers, selected kernels and cuBLAS through a Perl API | No PDL dependency or PDL compatibility claim |
| CPU preview | Dense float/double arrays, selected PDL operations, tested view semantics on both backends | Unsupported types, operations, and dataflow modes fail explicitly |
| CUDA preview | Single-device contiguous float/double operations and matrix multiplication on Linux with NVIDIA hardware | Explicit placement; no automatic acceleration of arbitrary Perl |
| Useful release | Documented API matrix, installation path, two representative workloads, reproducible performance evidence | Selected application compatibility rather than all of PDL |
| Later research | More types, bad values, broader views/dataflow, FFTs, selected PP-generated operations | Each addition needs its own compatibility and cost assessment |

CPU support is valuable independently and should work on macOS, Linux, and
Windows. Start CUDA qualification on Linux x86_64; qualify Windows separately.
Use a remote Linux GPU machine for CUDA development from a Mac. NVIDIA's
[macOS developer tools](https://developer.nvidia.com/nvidia-cuda-toolkit-12_0_0-developer-tools-mac-hosts)
are for remote workflows; this proposal does not target local CUDA execution on
Apple Silicon. Metal, OpenCL, multi-GPU execution, automatic differentiation,
and transparent distributed execution are outside the initial scope.

## Evidence and starting point

The following repository observations are a baseline, not a fresh diagnosis:

- The [CPAN failure report](../cpan-reports/cpan-compatibility-fail.dat)
  records PDL as `FAIL`, with `Unknown test outcome`, dated 2026-09-04.
  The [pass report](../cpan-reports/cpan-compatibility-pass.dat) contains some
  PDL-related distributions; those records do not establish working PDL core.
- [XSLoader.java](../../src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java)
  provides Java-backed module initialization. This is an integration seam,
  not a CPython-style or Perl XS binary compatibility layer.
- [build.gradle](../../build.gradle) selects Java 24, and [jperl](../../jperl)
  enables native access. The existing
  [FFM implementation](../../src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosix.java)
  demonstrates native integration; its executable default enables FFM despite
  an older class comment saying otherwise. CUDA needs its own binding and
  resource model, separate from POSIX.

Upstream [PDL::PP](https://metacpan.org/pod/PDL::PP) generates C/XS routines and
supports embedded native code, type handling, and broadcasting signatures.
Calling a shared library through FFM does not supply Perl's interpreter ABI,
SV objects, or XS initialization. Therefore, loading existing PDL XS binaries
directly is not the proposed route, and translating arbitrary PP code into Java
or CUDA is not an initial deliverable.

PDL compatibility also extends beyond array values: upstream describes both
shared-memory affine views and lazy propagation through transformations in its
[internals discussion](https://pdl.perl.org/advent/blog/2024/12/20/pdl-internals/).
These semantics make a full replacement a substantial, open-ended project.
CPU-first is the recommendation when existing PDL source compatibility is the
objective. When GPU computation is the objective and PDL integration proves
difficult, prefer direct CUDA. These are engineering judgments based on the
constraints; no PDL/CUDA prototype or performance measurement exists here yet.

## NumPy compatibility: choose the level deliberately

NumPy is relevant both as an interoperability target and as the performance
baseline. A NumPy-style interface in Perl does not execute Python source or
support arbitrary SciPy/Python extension modules. Separate four promises:

| Compatibility level | Feasible route | Limit and recommendation |
|---|---|---|
| Array data | Numeric `.npy` files first; shared host storage or DLPack later | Recommended early; exchanging arrays does not reproduce NumPy operations |
| Numerical semantics | A documented Perl subset modeled on the Python Array API, checked against NumPy | Recommended for new CPU/GPU applications; neither full NumPy nor formal Python API conformance |
| Actual NumPy execution | Optional persistent CPython worker, or an embedded CPython bridge | Reuses installed NumPy behavior; includes deployment and boundary costs |
| Python source / C-extension ABI | A Python runtime and its extension environment | Outside a PerlOnJava array implementation; do not reimplement CPython's ABI as this project's shortcut |

The [Python Array API standard](https://data-apis.org/array-api/latest/) provides
a bounded semantic reference across array libraries. Pin a released specification
and a NumPy version in the implementation contract; identify differences between
the chosen standard and ordinary NumPy explicitly. Begin with contiguous
float32/float64, explicit casts, arithmetic, reductions, reshape, and matrix
multiplication. Specify axis order, trailing-dimension broadcasting, scalar
promotion, indexing, copy/view behavior, and errors. Defer object/structured
dtypes, arbitrary advanced indexing, subclasses, and custom ufuncs.

Use the proposed `PerlOnJava::NDArray` namespace for this contract if selected.
Keep any PDL adapter separate: PDL dimension/broadcasting and mutation semantics
must not silently become NumPy semantics. Both adapters can share storage and
native kernels only where their operation contracts agree. D1 direct CUDA
remains possible without either adapter.

### Data interchange before runtime embedding

Implement a restricted numeric subset of
[NumPy's `.npy` format](https://numpy.org/doc/stable/reference/generated/numpy.lib.format.html)
as a portable first exchange mechanism. Validate dtype, byte order, shape,
payload length, and C/Fortran ordering. Reject object arrays and pickle payloads;
do not silently reinterpret unsupported types. Round-trip files through real
NumPy in optional integration tests. Files provide interoperability, not a
zero-copy claim or a fast per-operation transport.

Later, shared CPU buffers can avoid payload copies when layout, alignment,
lifetime, and synchronization agree. A Python worker can map shared host storage
and exchange metadata/control messages; process-local pointers cannot be passed
across processes as usable addresses. Start with owned copies and measure them
before taking on shared memory cleanup and concurrent mutation semantics.

[NumPy interoperability documentation](https://numpy.org/doc/stable/user/basics.interoperability.html)
describes DLPack and array conversion/dispatch protocols. NumPy arrays are CPU
arrays; a CUDA buffer cannot become a NumPy array without a host transfer.
GPU consumers such as CuPy are a different interchange target. An in-process
DLPack bridge needs a Python-facing wrapper as well as the native descriptor,
owner/deleter handling and stream coordination. DLPack is neither a computation
engine nor, by itself, a cross-process CUDA transport. GPU IPC is deferred.

### Reusing actual NumPy

A persistent CPython worker is the most isolated feasibility route when exact
NumPy functions or Python packages are required. Submit whole numerical batches,
retain arrays under worker-owned handles, and return small results or bulk
buffers. Pin Python/NumPy and dependencies, define cancellation and worker death
behavior, and account for worker startup, IPC, and copies. This can deliver useful
integration, but executing the same NumPy calculation behind an extra boundary
is not in itself an acceleration strategy.

Embedding CPython is an alternative to investigate only if measured IPC costs
justify it. Jep's
[DirectNDArray](https://ninia.github.io/jep/javadoc/3.9/jep/DirectNDArray.html)
documents a direct-buffer NumPy integration mechanism, showing a possible bridge.
That historical API documentation is evidence of an approach, not qualification
of a current dependency: pin and test a current Jep/CPython/NumPy combination,
JDK support, thread restrictions, buffer ownership and interpreter shutdown in
a separate spike. Do not assume Java heap arrays cross this boundary without
copying, or that embedding removes Python's threading/extension constraints.

The [NumPy C API](https://numpy.org/doc/stable/reference/c-api/array.html)
requires NumPy initialization and Python objects. FFM alone does not turn it
into a standalone numerical library. For independent fast kernels, call BLAS,
LAPACK, or CUDA directly; for exact NumPy execution, retain an actual Python
runtime. Select this bridge for ecosystem access, not as a default dependency
of every PerlOnJava process.

## A performance story that earns the warmup cost

The proposed product claim is: **Perl orchestration over reusable, typed numerical
pipelines, with competitive native kernels and fewer intermediate allocations
and transfers.** This is a target to prove, not a current capability. Merely
running Perl on a JIT or exposing CUDA does not establish an advantage over
Python's numerical ecosystem.

[NumPy linear algebra already uses BLAS/LAPACK](https://numpy.org/doc/stable/reference/routines.linalg.html).
Calling equivalent libraries can reach similar kernel throughput, but generally
leaves startup, conversion, and dispatch as additional costs. Likewise, compare
CUDA work with CuPy on the same GPU, not only with CPU NumPy. CuPy documents
[GPU timing, context initialization, compilation caching and fusion](https://docs.cupy.dev/en/stable/user_guide/performance.html);
these are existing competitor capabilities, not unique selling points.

### Where an advantage could come from

1. **Typed bulk execution:** keep arrays in compact buffers throughout ingestion,
   computation, and output. Bind a tuned CPU BLAS for large matrix operations;
   Java loops are a correctness baseline and candidate for elementwise kernels,
   not an assumed replacement for tuned GEMM. Measure the packed-buffer boundary
   as well as computation; repeated scalar boxing can erase kernel gains.
2. **Explicit reusable plans:** after the eager API works, offer a bounded
   expression/plan API that can fuse a chain such as affine transformation,
   clipping, and reduction. One fused pass can avoid full-size intermediates and
   multiple dispatches. Start with maintained fused kernels, then consider
   generating Java/CUDA kernels for the plan. This is typed array compilation,
   not speculative compilation of arbitrary Perl code.
3. **Resident pipelines:** reuse device allocations and library handles; upload
   once and download the final result. On CPU, reuse output/scratch buffers when
   aliasing permits. Plans must define mutation barriers, output ownership,
   exceptional values, and floating-point reassociation before fusion is allowed.
4. **Long-lived application execution:** batch jobs or explicitly persistent
   workers amortize JVM and GPU initialization. A persistent worker needs bounded
   memory, per-request state isolation, cancellation and recovery. Do not promise
   to run arbitrary CLI scripts repeatedly in one interpreter with unchanged
   semantics. Compare against equally persistent Perl/Python workers.

Reduced-precision math, extra threads, and a faster GPU are not language-level
advantages. Publish them as configuration choices. Python can also fuse work
using tools such as CuPy's fusion facilities; benchmark equivalent optimizations
where available. If the only win is against an unfused expression, state exactly
that. A well-integrated Perl API with competitive speed is still useful, but
should not be advertised as a general Python performance win.

### Startup and break-even accounting

Measure fresh-process latency independently of steady-state throughput. Include
JVM startup, Perl compilation/module loading, Python/NumPy imports when used,
native library loading, CUDA context creation, and first kernel/plan compilation.
Record the latency of the first useful result and the warmup curve; do not treat
an arbitrary number of discarded iterations as proof of steady state.
The [earlier startup investigation](pr328-startup-performance.md) is historical
context, not a current benchmark or a lower bound on achievable startup time.

For a fixed workload size, use the approximate model:

```text
T_POJ(N) = setup_POJ + N * batch_POJ
T_ref(N) = setup_ref + N * batch_ref
N_break_even > (setup_POJ - setup_ref) / (batch_ref - batch_POJ)
```

Each batch includes dispatch, conversion, transfers, computation, synchronization,
and output. Attribute excess JIT warmup time to setup for this approximation;
measure the actual cumulative curve when batches have changing costs. If
`batch_POJ >= batch_ref` and PerlOnJava has greater setup cost, there is no
amortization-based win. For illustration only, 400 ms extra setup and 2 ms saved
per batch requires more than 200 batches to pull ahead. These are not measurements.

Keep startup work as a separate workstream: lazy optional module loading,
reusable compilation artifacts, and JVM startup techniques may help, but need
fresh measurements and their own proposals. Numerical throughput cannot repair
the user experience of tiny one-shot CLI programs. Market any demonstrated
advantage for the workload duration and size where it actually holds.

### Required performance evidence

| Experiment | Baselines | Required report |
|---|---|---|
| Fresh process, tiny array | Native Perl, native Perl + PDL, Python + NumPy | Launch-to-result latency, RSS, repeated independent launches; show expected losses too |
| Dense CPU GEMM | PDL and NumPy with recorded BLAS/thread settings | Matched shape/precision, kernel and end-to-end timings, conversion costs |
| Elementwise/reduction chain | Eager NumPy/PDL plus available fused equivalent | Allocations, bytes moved, throughput, plan construction/compilation and reuse count |
| Resident GPU pipeline | CuPy eager and equivalent fused/library path | Same GPU/math mode, synchronized timings, transfer counts, cold context/cache and warm cases |
| Repeated application batches | Equally persistent Perl/Python applications | Cumulative elapsed time, break-even batches, latency distribution and peak memory |

Run size sweeps including cache-sized and memory-bandwidth-limited arrays; record
versions, host/GPU hardware, BLAS thread limits, precision/tolerances, and cache
state. Separate JVM/interpreter correctness from their measured orchestration
costs. Use meaningful numerical outputs to prevent dead-code elimination.

Proposed release gate: competitive GEMM (target within 20% end-to-end on large
matched workloads) and at least one useful pipeline with a repeatable 1.5x
end-to-end improvement over the best tested relevant baseline, with uncertainty
reported and a measured break-even inside the intended job lifetime. These are
decision thresholds to revisit at feasibility, not promises. If the gate fails,
ship an interoperability preview with accurate results, or narrow the performance
claim; do not broaden the benchmark claim to all Perl/Python programs.

## Direct CUDA without PDL

This is a first-class route and the preferred fallback if the PDL investigation
uncovers extensive loader, generated XS, or dataflow dependencies. It can also
be selected immediately for new GPU applications. A useful CUDA binding does
not require implementing PDL, its views, its type promotion, or a full CPU array
engine. It still requires careful native ABI, memory, context, and numerical
validation.

Propose an experimental `PerlOnJava::CUDA` module with a deliberately small
surface: capability discovery, typed allocation, upload/download, explicit
close, elementwise operations, and float/double GEMM. Initially use contiguous
buffers, explicit dimensions and dtypes, and immutable operation results.
Choose and document matrix layout in this API; no PDL dimension convention is
implied. Both execution backends call the same Java implementation.

The intended flow is: create a context, upload packed numeric data, run several
operations on device handles, download the final output, close the resources.
Bulk packed data avoids boxing every input element as a Perl scalar. Specify
byte order, element width, length validation, and copying behavior; an optional
array-reference convenience constructor can come later with its cost documented.
Do not expose device pointers as user-editable Perl integers.

This route needs only small host reference calculations for tests, not the M2
CPU compatibility layer. Test public API behavior on both PerlOnJava backends;
use ordinary system Perl arithmetic or fixed analytical results as the numerical
oracle where appropriate. System Perl cannot load the proposed JVM-specific
module, so keep its pure numerical oracle separate from integration tests.

Begin with maintained kernels and cuBLAS. Arbitrary user CUDA source, NVRTC
compilation, stream/event control, and broad driver bindings are later additions.
Even without those additions, resident vector pipelines and matrix multiplication
could be useful. Applications explicitly require a CUDA-capable machine;
automatic CPU fallback is not part of this route's first release.

### Route decision

Time-box the initial PDL source/loader investigation to a suggested three
engineering days, then record a decision; this is a scope limit, not an estimate
for completing support. Continue the PDL route only if a concrete application
needs it and the bounded wrapper/semantic surface is manageable. Select direct
CUDA if new GPU workloads are sufficient, if basic PDL loading needs broad XS
emulation, or if the required application depends on deferred dataflow/PP features.
An unresolved PDL report must not hold CUDA milestones open indefinitely.

The direct API can later serve as a GPU executor for a PDL adapter. Keep its
native resource layer reusable, but do not burden its first release with PDL
compatibility abstractions. If existing PDL applications are the only acceptance
criterion, direct CUDA alone does not meet that criterion: record the application
rewrite required or evaluate the external Perl worker instead.

## Architecture and alternatives

```text
Perl application (JVM backend or interpreter)
                    |
     Perl compatibility layer / Java module entry points
                    |
        Array descriptor + operation contract
                    |
          +---------+----------+
          |                    |
    Java CPU executor    Optional CUDA executor
    dense typed buffers  FFM -> driver / cuBLAS / kernels
```

Prefer an experimental `PerlOnJava::NDArray` entry point while the contract is
being established. A later, explicitly selected PDL compatibility package can
reuse upstream Perl wrappers where practical and register Java implementations
at the XS boundary. Names in this document are proposed, not existing APIs.
Do not silently shadow an installed `PDL`, advertise upstream's complete
version contract, or mark the entire distribution supported based on a subset.
Milestone 1 must decide packaging and how opt-in compatibility is selected.

| Approach | Benefit | Cost / decision |
|---|---|---|
| Direct Perl CUDA module via FFM | Shortest proposed route to useful GPU operations; no PDL port | Preferred when PDL integration is costly and source compatibility is optional; requires a new application API |
| Java array core plus FFM CUDA | Fits existing module/native integration; one semantic contract across backends | Recommended; PDL behavior must be implemented and tested |
| Extract PDL's C core behind a new C ABI | Potential reuse of numerical code | Spike only if a small boundary can be demonstrated; inspect Perl dependencies and ownership first |
| Embed native Perl and PDL in the JVM | Potential access to more existing modules | Two runtimes, object conversion, lifecycle and threading complexity; defer |
| External native Perl worker | Existing PDL can run in a separate process | Practical escape hatch for applications; serialized bulk data and separate identity, not in-process compatibility |
| Existing Java numerical/GPU library | Could reduce kernel and binding work | Evaluate during feasibility against PDL layout, dtype, view semantics, dependencies, and deployment; no library selected yet |

An external worker must be launched as a child process, not through PerlOnJava
`fork`. Its protocol would need versioned dtype/shape metadata, bounded payloads,
error propagation, and explicit copy semantics. It is an alternative if upstream
compatibility dominates the requirement, not a prerequisite for the main plan.

### Array and operation contract

Keep numerical elements out of per-element `RuntimeScalar` objects. A descriptor
should hold dtype, dimensions, element strides, offset, storage owner, mutability,
and residency. CPU primitive buffers and native staging allocations are distinct
storage implementations. Use checked dimension products and offset calculations;
document limits rather than assuming Java arrays can represent every PDL size.

Milestone 1 defines an executable compatibility matrix using a pinned system
Perl + PDL installation as the oracle:

- Construction and conversion: explicit float/double first; shape, rank-zero
  scalars, empty dimensions, and errors for unsupported types. Capture default
  dtype and mixed-scalar promotion before implementing compatibility wrappers.
- Indexing and broadcasting: preserve PDL dimension order and operation core
  dimensions. Do not import NumPy broadcasting rules by assumption. Test
  asymmetric dimensions, singleton expansion, mismatches, and empty results.
- Arithmetic and reductions: begin with add/subtract/multiply/divide and
  `sumover`; define output dtype, reduction axis, scalar coercion, NaN/infinity,
  and numerical comparison tolerances from oracle evidence.
- Views: implement a bounded `slice`/reshape subset with shared storage and
  correct parent/view mutation. Reject unsupported strides or materialize only
  where the operation contract permits copying. A copy must not silently replace
  a writable alias. Include overlapping assignment and `.=` behavior in tests.
- Deferred surface: full transformation dataflow, arbitrary PP extensions,
  complex/integer families, bad-value handling, raw pointer access, graphics,
  and scientific file formats. Reject requests for deferred behavior; BAD
  values must not be silently treated as ordinary NaNs.

An operation descriptor defines inputs, output shape/type, supported layouts,
aliasing rules, and implementations. Keep this numerical interface independent
of Perl AST compilation. Ordinary Perl operator overload dispatch should enter
bulk operations; do not attempt to compile arbitrary Perl loops into GPU kernels.

### CUDA implementation

Use a small FFM binding to the
[CUDA Driver API](https://docs.nvidia.com/cuda/cuda-driver-api/index.html)
for device discovery, contexts, allocations, copies, synchronization, and kernel
launches. Bind symbols and ABI widths against the pinned toolkit headers.
Represent device addresses as opaque device handles: GPU allocation addresses
are not host memory that Java can dereference through `MemorySegment`.

Start with one device, one managed execution context, and synchronous public
operations. Retain and release the primary context deliberately, and establish
the current context on every host thread that makes driver calls. Do not rely
on a context remaining current when interpreter or JVM execution changes threads.
Initially reject sharing device objects across Perl ithreads, with a clear error;
define cloning/transfer support before relaxing that restriction.

Use [cuBLAS](https://docs.nvidia.com/cuda/cublas/index.html) for dense float/double
matrix multiplication. Specify PDL dimensions, leading dimensions, transpose
flags, output ownership, and compute precision explicitly; verify with rectangular
matrices so layout errors cannot hide. Fast reduced-precision modes require a
separate opt-in contract. FFTs and other CUDA libraries come after this boundary
works reliably.

For elementary operations, start with a handful of maintained kernels compiled
to a versioned artifact. An optional later
[NVRTC](https://docs.nvidia.com/cuda/nvrtc/index.html) path can compile controlled
CUDA C++ templates and load the resulting GPU code through the driver. NVRTC
does not translate Perl or arbitrary PDL::PP. Record GPU architecture, compiler
version, options, and source hash in any compilation cache key.

Expose explicit CPU-to-device and device-to-CPU operations in the experimental
API, plus capability discovery. CPU mode must load without any CUDA installation;
an explicit CUDA request must fail clearly when unavailable. Unsupported GPU
operations should error in the preview. Add automatic CPU fallback only later
with visible transfer diagnostics and a defined policy.

Start with immutable GPU results and reject mutable GPU views. Repeated operations
can retain device storage even if each call synchronizes initially. Host access
must explicitly download a completed result. This keeps the first residency
contract small while CPU aliases remain independently correct.

Every allocation has an owner and idempotent close path. Pending native work
must retain buffers until completion; a later asynchronous implementation needs
events and deferred reclamation before arenas can close or device memory can be
freed. Integrate with Perl object destruction, but retain a Java cleanup fallback
for abandoned objects. Test errors during partial allocation and shutdown.
Translate driver/library failures into Perl exceptions with operation and device
context; never silently retry a partly executed mutation on CPU.

### Deployment and performance boundaries

Keep CUDA optional and lazily initialized. Select and record a tested driver,
toolkit, GPU architecture, OS, JDK, and native library discovery matrix during the
GPU spike, following NVIDIA's
[Linux installation guide](https://docs.nvidia.com/cuda/cuda-installation-guide-linux/).
Do not require a toolkit or GPU for the ordinary Java build and CPU tests.
Determine which libraries/artifacts are user-installed versus distributed before
packaging; review their redistribution terms at that point.

GPU benefit is most plausible for sufficiently large resident pipelines and
matrix multiplication. Small arrays, scalar-at-a-time access, and repeated
upload/download can lose to CPU execution. Benchmark cold initialization,
host/device transfer, synchronized kernel/library time, and end-to-end time
separately. Report sizes, precision, warmup, hardware, and peak host/device memory.
Compare against both the Java CPU implementation and native Perl PDL, including
the numerical libraries and thread counts used by each. No speedup is promised
until end-to-end results demonstrate it.

## Milestones and acceptance gates

Choose a route at M1 using the benchmark baseline below. For PDL compatibility, follow M2 through M5. For direct
CUDA, skip M2 and use D1 through D3 below; the CUDA resource, deployment, and
performance requirements above apply to both routes. M3/M4 describe shared
capabilities that need implementing only once.

NumPy interoperability and performance have separate gates. N1/P0 can proceed
without GPU hardware; N2 is conditional on a real Python-package requirement.
P1 follows a correct eager CPU or CUDA implementation. Do not build every route
before deciding which workload supplies the performance case.

| Gate | Deliverable | Exit evidence / handoff |
|---|---|---|
| P0 — performance baseline | Tracked benchmark harness for cold starts, CPU/GPU kernels, pipelines and repeated batches | Pinned NumPy/PDL baselines and CuPy on available hardware; raw measurements, reproducible commands and selected target job lifetime; hardware gaps explicit |
| N1 — array interchange and semantics | Numeric `.npy` round trips; versioned NumPy-style/Array API subset decision | Real NumPy reads/writes fixtures; documented dtype/layout/rejection matrix; distinguish PDL and NumPy contracts |
| N2 — optional actual NumPy bridge | Persistent CPython worker spike; investigate embedding only if needed | Representative package calls, measured boundary costs, resource/error tests, pinned dependency matrix; accept for interoperability only unless performance is demonstrated |
| P1 — reusable numerical plans | One supported fused CPU or GPU pipeline with explicit numerical/mutation rules | Correctness on both Perl backends, reduced allocations/transfers, cold compilation and steady-state comparisons against equivalent optimized baselines |
| P2 — performance qualification | Size/reuse sweeps and cumulative break-even report | Proposed performance gates assessed; accurately bounded claims; release decision and remaining startup limitations recorded |

| Direct milestone | Deliverable | Exit evidence / handoff |
|---|---|---|
| D1 — CUDA binding and API spike | `PerlOnJava::CUDA` API contract, FFM context/buffer ownership, packed upload/download, one kernel and rectangular GEMM | Real Linux GPU execution from JVM and interpreter; exact ABI/version inventory; absence and cleanup tests; no PDL dependency |
| D2 — useful resident computation | Maintained elementwise kernels, explicit dimensions/precision, retained device buffers, error handling | Two example workloads, independent numerical oracle, transfer/memory accounting, synchronized end-to-end benchmarks |
| D3 — optional module preview | Installation and API docs, optional packaging, hardware CI and release gate | `make` passes; GPU integration passes on both backends; CPU-only load/probe behavior works; supported hardware/version matrix published |

Milestones are dependency gates, not calendar promises. Estimate implementation
effort after M1; availability of a maintained GPU runner gates M3 onward.

| Milestone | Deliverable | Exit evidence / handoff |
|---|---|---|
| M0 — design | This proposal and repository/upstream baseline | Complete; no implementation claim |
| M1 — feasibility and route decision | Time-box PDL investigation when compatibility is wanted; pin source and reproduce failure; choose direct CUDA or PDL route, packaging, and workloads | Recorded decision and bounded API contract; oracle plan; inventory hardware access; route to D1 if PDL cost is excessive |
| M2 — CPU subset | Descriptor/storage core, Java module entry points, construction, arithmetic, broadcasting, reduction, bounded views | System Perl oracle and both PerlOnJava backends agree on all supported cases; permanent failure tests; `make` passes; CPU-only demo runs |
| M3 — native CUDA spike | Optional FFM discovery, context, allocate/copy/free, one kernel, cuBLAS rectangular GEMM | Actual Linux GPU execution, explicit absence/error tests, repeated allocation cleanup, numerical agreement; no toolkit dependency in CPU build |
| M4 — resident pipeline | Explicit placement, retained device buffers, supported elementwise/reduction chain, matrix pipeline | Both Perl backends pass on hardware; transfer counters show no intermediate host round trips; synchronized benchmarks include transfer costs and memory usage |
| M5 — usable preview | Opt-in compatibility package, API support table, installation docs, CI jobs, two applications | Reproducible CPU/GPU release qualification, documented limitations, measured benefit for at least one target workload or an explicit experimental-only designation |
| M6 — selective expansion | Prioritized types, BAD semantics, dataflow, FFT, PP subset feasibility | Separate proposals and oracle coverage; expand compatibility claims only for demonstrated behavior |

Candidate workloads for M1 selection: normalize a batch of dense signals using
broadcast arithmetic and reductions; compute a dense matrix product with
elementwise post-processing. Keep ingestion in a simple supported format so
unrelated HDF5/FITS bindings do not gate the numerical prototype.

### Validation rules

Keep focused project-owned regression tests for every discovered failure.
The following upstream-PDL oracle requirements apply to the PDL route; the direct
route uses the separate numerical oracle described above.
Validate Perl-level tests with system Perl and the pinned PDL first, retain
evidence of failure on the unfixed PerlOnJava parent, then require success on
JVM and interpreter. New unit tests belong under
[src/test/resources/unit](../../src/test/resources/unit); use a separate optional
integration gate for tests requiring external PDL or GPU hardware. Do not make
ordinary unit tests depend on a locally installed upstream PDL.

Preserve upstream tests. Run selected upstream PDL tests as additional evidence,
and report passed/failed/skipped counts and the tested API surface. Native CUDA
tests need hardware coverage for ownership, context/thread changes, invalid
dimensions, allocation failure, and numerical edge cases; mocks cannot establish
GPU correctness. CPU-only CI verifies graceful absence; a GPU release gate must
fail rather than skip when its required device is missing.

Follow [AGENTS.md](../../AGENTS.md): capture full output, wrap every
`jperl`/`jcpan`/`prove` invocation with `timeout`, run `make` for implementation,
and keep a checkout immutable while its build/test gate runs. GPU jobs also need
bounded execution and cleanup of their own resources. Do not run a shared-JAR
writer beside numerical reader tests. Documentation-only work uses
`make check-links`.

## Handoff and progress tracking

### Current status: M0 complete (2026-09-08); M1 not started

- [x] M0: added `dev/design/pdl-cuda.md`; inspected module loading, Java/FFM
  configuration, CPAN reports, and upstream PDL/CUDA documentation.
- [x] Design extension (2026-09-08): added NumPy compatibility levels, optional
  Python bridge, array interchange, reusable-plan performance strategy,
  break-even model, and N/P milestone gates. Only this design file changed.
- [ ] M1–M6 / D1–D3 / N1–N2 / P0–P2: no route selected, implementation, PDL reproduction, GPU execution, or benchmarks
  performed as part of this design.

### Next steps

1. The next implementer starts M1 on a feature branch after the repository
   pre-flight. Record the exact PerlOnJava commit and versions of relevant
   PDL/NumPy dependencies. Start P0 with current cold-start and CPU baselines.
2. Determine whether an existing PDL application is required. If so, establish an
   isolated system Perl + PDL oracle and time-box failure/source investigation.
   Otherwise choose direct CUDA or the NumPy-style array surface for the selected
   workload. Define its buffer/API contract; actual NumPy package execution is
   an optional N2 decision, not a prerequisite.
3. For the PDL route, turn the proposed CPU surface into a checked-in matrix of operations,
   dtypes/layouts, expected values, mutation semantics, and unsupported errors.
   Decide experimental namespace and wrapper installation before touching loaders.
4. Identify a Linux NVIDIA runner and record its driver/device inventory. CPU
   work can proceed if unavailable; CUDA acceptance remains pending.
5. Deliver M1/P0 evidence and an updated effort estimate here before expanding
   scope. Select N1 interchange, D1 CUDA, or CPU work based on that evidence;
   prioritize P1 fusion only after the eager baseline identifies useful savings.

### Required handoff at every milestone

Record completion date, commit, files changed, accepted decisions, test/log
locations and summaries, dependency versions, remaining failures, and the next
bounded task. For CUDA work also record hardware, native ABI/library versions,
context ownership, synchronization assumptions, and benchmark reproduction
commands. Store permanent tests and benchmark scripts in the repository;
temporary log paths alone are insufficient. Update this document's status and
next steps when completing each phase.

### Open questions and blockers

- Which job size and lifetime must repay warmup: a one-shot command, a batch
  process, or a persistent service? No current break-even measurements exist.
- Does NumPy compatibility mean exchanging data, familiar operations in Perl,
  or executing existing Python packages? N1 and N2 address different needs.
- Is PDL source compatibility required, or is a new direct CUDA API sufficient?
  Direct CUDA is the preferred fallback when PDL integration is difficult.
- Which existing user application should define the minimum useful PDL subset?
  The two candidate workloads above are defaults until one is selected.
- How much upstream Perl wrapper code can be reused without exposing unsupported
  PDL internals? M1 must inspect the pinned source, not infer from module names.
- Is broad upstream compatibility more important than a self-contained JVM
  implementation? If so, reassess the external-worker alternative after M1.
- Which Linux GPU runner can be maintained for CI? Hardware access is unverified.
- Which dtypes, BAD semantics, and view operations are essential beyond the
  preview? Defer them explicitly until requirements and oracle tests justify them.

## Related project documents and implementation skills

- [XSLoader architecture](../modules/xsloader.md)
- [Dual-backend CPAN module proposal](DUAL_BACKEND_CPAN_MODULES.md) — consult code
  before assuming proposed external JAR packaging is implemented.
- [FFM migration design](ffm_migration.md)
- [Object lifetime and destruction](../architecture/weaken-destroy.md)
- [CPAN module porting skill](../../.agents/skills/port-cpan-module/SKILL.md)
- [Native module porting skill](../../.agents/skills/port-native-module/SKILL.md)
- [Interpreter parity skill](../../.agents/skills/interpreter-parity/SKILL.md)
