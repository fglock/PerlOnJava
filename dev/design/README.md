# PerlOnJava design documents

Proposals and plans for work that is being considered or is in progress.

| If you need | Read |
|---|---|
| How a subsystem works today | [`dev/architecture/`](../architecture/README.md) |
| What to work on next | [issue-queue.md](issue-queue.md) |
| Why a past change was made | [archive/](../archive/README.md), then git history |
| CPAN module porting notes | [`dev/modules/`](../modules/README.md) |

## Rules for documents in this directory

1. **One topic per file**, named in `kebab-case.md`. Do not put dates, issue
   numbers, or PR numbers in the name of a living document. Existing files
   keep their names so that links from issues and PRs continue to work.
2. **State the status near the top** on a line beginning `Status:` with one of
   `Proposal`, `Active`, `Implemented`, `Superseded`, or `Historical`, plus a
   date. The index below is generated from that line.
3. **Keep one current-state section.** Replace superseded next steps instead
   of appending a log. Record the delivered scope, source commit, and evidence
   links when a phase completes.
4. **Archive when finished.** When the work is implemented, superseded, or
   abandoned, move the durable description of current behavior into
   `dev/architecture/` and move the plan to `dev/archive/design/`. Handoff notes and
   single-issue investigations go there as soon as the issue closes.
5. **Regenerate the index** after adding, renaming, or archiving a document:
   `perl dev/tools/gen_design_index.pl`. `make check-links` fails when the
   index is stale.

## Index

<!-- BEGIN GENERATED INDEX -->

| Document | Title | Status |
|---|---|---|
| [attributes.md](attributes.md) | Attribute System Implementation Design | — |
| [auto_flush.md](auto_flush.md) | PerlOnJava Handle Auto-Flush Implementation Specification | — |
| [BARE_BLOCK_RETURN_FIX.md](BARE_BLOCK_RETURN_FIX.md) | Bare Block Return Value Fix | — |
| [bytecode_debugging.md](bytecode_debugging.md) | Bytecode debugging workflow (ASM verifier failures) | — |
| [caller_line_number_fix.md](caller_line_number_fix.md) | caller() Line Number Fix Plan | — |
| [catalyst-support.md](catalyst-support.md) | Catalyst Support Handoff | — |
| [clone.md](clone.md) | Strategy for Implementing Deep Cloning with Circular References | — |
| [concurrency.md](concurrency.md) | Perl Threads Completion Plan | Complete |
| [CONSTANT_FOLDING.md](CONSTANT_FOLDING.md) | Constant Folding for User-Defined Constants | — |
| [core-suite-failure-reduction.md](core-suite-failure-reduction.md) | Core-suite failure reduction | Phase 19 in progress — remaining op/write.t field-rendering… |
| [CORE_SUBROUTINE_REFERENCES.md](CORE_SUBROUTINE_REFERENCES.md) | CORE:: Namespace: Subroutine References for Built-in Functions | — |
| [cpan-port-prioritization.md](cpan-port-prioritization.md) | CPAN port and bundling prioritization | — |
| [cpan-runtime-parity-experiment.md](cpan-runtime-parity-experiment.md) | CPAN Runtime Parity Experiment | — |
| [cpan-workaround-elimination.md](cpan-workaround-elimination.md) | Eliminating CPAN compatibility workarounds | — |
| [cryptx-port.md](cryptx-port.md) | CryptX Java XS Port | — |
| [dbixclass-timeout-jit-analysis.md](dbixclass-timeout-jit-analysis.md) | DBIx::Class t/96isdeteministicvalue.t — Timeout Root Cause Analysis | — |
| [debugger.md](debugger.md) | Debugging PerlOnJava with JPDA | — |
| [defer_blocks.md](defer_blocks.md) | Defer Blocks Implementation Design | — |
| [destroy_weaken_plan.md](destroy_weaken_plan.md) | DESTROY and weaken() — Design & Status | Moo 71/71 (100%); DBIx::Class broad test suite passing |
| [distro.md](distro.md) | PerlOnJava distro | — |
| [docs.md](docs.md) | Documentation Improvement Plan | — |
| [DUAL_BACKEND_CPAN_MODULES.md](DUAL_BACKEND_CPAN_MODULES.md) | Dual-Backend CPAN Modules (Option B) | — |
| [dynamic_variables.md](dynamic_variables.md) | Implement parser: | — |
| [feature-audit-plan.md](feature-audit-plan.md) | PerlOnJava Feature Audit Plan | — |
| [FEATURE_FLAGS.md](FEATURE_FLAGS.md) | Control Flow Feature Flags | — |
| [ffm_migration.md](ffm_migration.md) | FFM Migration: Replace JNR-POSIX with Java Foreign Function & Memory API | — |
| [fix_unit_test_parity.md](fix_unit_test_parity.md) | Fix Unit Test Parity with System Perl | — |
| [for_underscore_aliasing_bug.md](for_underscore_aliasing_bug.md) | Bug: for $ with Transliteration Doesn't Modify Array Elements | — |
| [fork_continuation.md](fork_continuation.md) | Full-Call-Chain Continuations for Pseudofork | proposed implementation subplan for pseudofork issue #1144… |
| [fork_open_emulation.md](fork_open_emulation.md) | Perl fork Emulation (issue #1144) | investigation complete; implementation is not started |
| [function-parameters-compatibility.md](function-parameters-compatibility.md) | Function::Parameters compatibility | Core subset implemented; upstream compatibility work remains… |
| [functional_interface_implementation.md](functional_interface_implementation.md) | PerlSubroutine Functional Interface Implementation Plan | — |
| [functional_subroutines.md](functional_subroutines.md) | Functional Interface Approach for PerlOnJava Subroutines | — |
| [future_asyncawait.md](future_asyncawait.md) | Future::AsyncAwait on PerlOnJava | — |
| [graalvm.md](graalvm.md) | GraalVM Native Image Support Investigation | — |
| [http_server.md](http_server.md) | PerlOnJava HTTP Server Implementation Proposal | — |
| [inc_in_jar.md](inc_in_jar.md) | Adding Perl "pm" files to the jar and accessing them from @INC | — |
| [incubating.md](incubating.md) | — | — |
| [initialize_modules.md](initialize_modules.md) | — | — |
| [inline-cache.md](inline-cache.md) | Inline Caching in PerlOnJava: Technical Implementation Guide | PARTIALLY IMPLEMENTED - A runtime global inline cache (4096… |
| [interpreter.md](interpreter.md) | Fast Interpreter Architecture for PerlOnJava | — |
| [interpreter_benchmarks.md](interpreter_benchmarks.md) | Interpreter Architecture Benchmarking Results | — |
| [INTERPRETER_OPTIMIZATION.md](INTERPRETER_OPTIMIZATION.md) | Interpreter Performance Optimization | — |
| [io_handle_lifecycle.md](io_handle_lifecycle.md) | IO Handle Lifecycle & Fileno Registry | — |
| [issue-1136-io-async-refcount.md](issue-1136-io-async-refcount.md) | Issue #1136: IO::Async B::SV Refcount Alignment | Phase 1 implemented; CPAN acceptance verification pending |
| [issue-1508-clone-deferred-strings.md](issue-1508-clone-deferred-strings.md) | Issue 1508: Clone deferred strings | Complete (2026-09-24) |
| [issue-organization.md](issue-organization.md) | Issue organization and agent coordination | minimal workflow implemented for |
| [issue-queue.md](issue-queue.md) | Reviewed issue queues | — |
| [jdwp_debugger.md](jdwp_debugger.md) | To support JDWP (Java Debug Wire Protocol) in a language like Perl running on the JVM | — |
| [jni_native_perl.md](jni_native_perl.md) | — | — |
| [jsr223-perlonjava-web.md](jsr223-perlonjava-web.md) | PerlOnJava JSR-223 and Web Integration Design | — |
| [lexical-warnings.md](lexical-warnings.md) | Lexical Warnings: Full Perl 5 Parity | — |
| [local-package-variable-fix.md](local-package-variable-fix.md) | Fix Plan: local Package Variable Bug | — |
| [local_variable.md](local_variable.md) | — | — |
| [local_variable_codegen.md](local_variable_codegen.md) | Local Variable Declaration Code Generation Design | — |
| [logical_or_list_context.md](logical_or_list_context.md) | Deep Dive: \|\| Operator in List Context | — |
| [maven-central-publishing.md](maven-central-publishing.md) | Maven Central Publishing for PerlOnJava | Design refreshed; implementation not started |
| [method-call-optimization.md](method-call-optimization.md) | Method Call Performance Optimization Plan | — |
| [module_build_test_fixes.md](module_build_test_fixes.md) | Module::Build Test Pass Rate Improvement Plan | — |
| [nested-eval-string-lexicals.md](nested-eval-string-lexicals.md) | Fix: nested eval STRING cannot see outer my lexicals (interpreter backend) | — |
| [net-async-http-uat-blockers.md](net-async-http-uat-blockers.md) | Net::Async::HTTP UAT Blockers | Implemented and validated; PR #1213 is based on master and… |
| [NUMERIC_WARNINGS_IMPLEMENTATION.md](NUMERIC_WARNINGS_IMPLEMENTATION.md) | Numeric Warnings Implementation Plan | — |
| [operator_factory.md](operator_factory.md) | — | — |
| [optimization.md](optimization.md) | Optimization Suggestions | — |
| [optimization_codegen.md](optimization_codegen.md) | — | — |
| [pack_unpack_architecture.md](pack_unpack_architecture.md) | Pack/Unpack Architecture Overview | Living document - updated based on deep dive bug fixing |
| [package-variable-binding.md](package-variable-binding.md) | Package variable binding: compile-time glob references | design (no implementation yet) |
| [parser-cpan-compatibility-providers.md](parser-cpan-compatibility-providers.md) | Parser CPAN compatibility providers | — |
| [parser-high-impact-completion-plan.md](parser-high-impact-completion-plan.md) | Completion plan for the five high impact parser issues | — |
| [parser_scalar_slice_precedence.md](parser_scalar_slice_precedence.md) | Parser Bug: scalar @array[indices] Precedence | — |
| [patch-and-cpan-prefs-layout.md](patch-and-cpan-prefs-layout.md) | Patch and CPAN distropref layout | Adopted (2026-05-12). Canonical locations for Perl 5 import… |
| [pdl-cuda.md](pdl-cuda.md) | Numerical performance for PerlOnJava: PDL, NumPy, and CUDA | proposed; implementation has not started. Research baseline… |
| [perf-dbic-safe-port.md](perf-dbic-safe-port.md) | DBIC-Safe Perf Port Plan | — |
| [performance-delivery-selection.md](performance-delivery-selection.md) | Curated performance delivery selection | — |
| [performance-over-perl.md](performance-over-perl.md) | Performance over Perl benchmark authority | — |
| [perl-ownership-rewrite.md](perl-ownership-rewrite.md) | Perl-compatible ownership rewrite | Phase 1 closure-pad path is implemented and validated on… |
| [perl-regex-library-rfc.md](perl-regex-library-rfc.md) | RFC: Embeddable Perl-Compatible Regex Library for the JVM | — |
| [perl-summit.md](perl-summit.md) | — | — |
| [perl_debugger.md](perl_debugger.md) | Perl Debugger Implementation for PerlOnJava | — |
| [perl_framework_support.md](perl_framework_support.md) | Perl HTTP Framework Support Roadmap | — |
| [perlio-via.md](perlio-via.md) | PerlIO::via Runtime Layers | Design ready (2026-05-28). Implementation pending |
| [posix.md](posix.md) | — | — |
| [ppr-compatibility.md](ppr-compatibility.md) | PPR 0.001010 Compatibility | — |
| [pr328-startup-performance.md](pr328-startup-performance.md) | PR #328 Startup Performance Investigation | — |
| [project-organization.md](project-organization.md) | Project organization review and cleanup plan | Active — 2026-11-20. Phases 1–3 implemented. AGENTS.md… |
| [reachability-walk-cache-plan.md](reachability-walk-cache-plan.md) | Reachability Walk Cache for DBIC Scope Cleanup | Phase 3 complete; CPAN verification passed for DateTime… |
| [reduce-apply-bytecode.md](reduce-apply-bytecode.md) | Design: Reduce Generated apply() Method Bytecode Size | — |
| [refcount-owner-ledger.md](refcount-owner-ledger.md) | Perl-Exact Reference Ownership and Closure-Pad Lifetime | Design in progress |
| [refcount_alignment_52leaks_plan.md](refcount_alignment_52leaks_plan.md) | DBIC ./jcpan -t DBIx::Class — Refcount Alignment Plan | Active (Phase H in progress) |
| [refcount_alignment_plan.md](refcount_alignment_plan.md) | Aligning PerlOnJava Reference Counting with Perl Semantics | Proposal / Design Doc |
| [refcount_alignment_progress.md](refcount_alignment_progress.md) | Refcount Alignment Progress | — |
| [regex-implementation.md](regex-implementation.md) | Full Perl Regex Implementation | — |
| [repl.md](repl.md) | — | — |
| [runtime-pooling-reset-contract.md](runtime-pooling-reset-contract.md) | Runtime Pooling Reset Contract | — |
| [sbom.md](sbom.md) | SBOM (Software Bill of Materials) for PerlOnJava | Implemented |
| [scriptingapi.md](scriptingapi.md) | — | — |
| [shared_ast_transformer.md](shared_ast_transformer.md) | Shared AST Transformer for Backend Parity | — |
| [source_filters.md](source_filters.md) | Source Filters Implementation Design | — |
| [strict_hints_refactor.md](strict_hints_refactor.md) | Strict/$^H Refactoring Design | Analysis complete, implementation deferred |
| [strict_vars_double_eval_issue.md](strict_vars_double_eval_issue.md) | Strict Variables Double Eval Issue | — |
| [string_encoding_context_plan.md](string_encoding_context_plan.md) | Plan: Fix PerlOnJava Encoding Context Handling During String Operations | — |
| [superoperators.md](superoperators.md) | Superoperator Design for PerlOnJava Bytecode Interpreter | — |
| [symbol_table_manipulation.md](symbol_table_manipulation.md) | Create a scalar | — |
| [TAINT_MODE.md](TAINT_MODE.md) | Taint Mode Implementation Plan | — |
| [todo.md](todo.md) | TODO | — |
| [unicode-encode-compatibility.md](unicode-encode-compatibility.md) | Unicode and Encode compatibility | — |
| [utf16_sourcecode.md](utf16_sourcecode.md) | UTF-16 Source Code Handling Fix | — |
| [utf8_flag_parity.md](utf8_flag_parity.md) | UTF-8 Flag Parity: Byte-String Preservation | — |
| [variables_and_values.md](variables_and_values.md) | PerlOnJava Variable and Value System Documentation | — |
| [versioning.md](versioning.md) | Gradle | — |
| [weighted-test-scheduler.md](weighted-test-scheduler.md) | Weighted Semantic Test Scheduling | Implemented and locally validated |
| [windows_installer.md](windows_installer.md) | Windows Installer Build Configuration | — |
| [xml_parser_xs.md](xml_parser_xs.md) | XML::Parser Java XS Implementation Plan | — |

<!-- END GENERATED INDEX -->
