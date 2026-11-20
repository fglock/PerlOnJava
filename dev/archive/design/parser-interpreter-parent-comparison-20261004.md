# Interpreter parent comparison (2026-10-04)

Candidate `977864670` full inventory: 2,080 pass, 35 fail, 10 error, 4 incomplete, 0 timeouts (2,129 files).
The exact 49 nonpassing files were rerun on parent `75681d00d`; candidate and parent match in status, failed assertions, actual/planned TAP counts, and error count for every row below.
The candidate `make test-interpreter` target exits zero with nonpassing files; the counts above are from its captured JSON summary.

| Test file | Status on both | Failed assertions | TAP run/planned | Errors |
|---|---:|---:|---:|---:|
| `src/test/resources/unit/anonymous_code_attribute_order.t` | fail | 1 | 2/2 | 0 |
| `src/test/resources/unit/array_autovivification.t` | fail | 4 | 9/9 | 2 |
| `src/test/resources/unit/b_cv_subidentify_metadata.t` | fail | 1 | 6/6 | 0 |
| `src/test/resources/unit/begin_undef_assignment.t` | error | 1 | 1/1 | 4 |
| `src/test/resources/unit/caller_wantarray_context.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/can_missing_subroutine.t` | fail | 1 | 3/3 | 0 |
| `src/test/resources/unit/class_method_field_refcount.t` | incomplete | 1 | 0/1 | 0 |
| `src/test/resources/unit/closure.t` | fail | 1 | 18/18 | 0 |
| `src/test/resources/unit/code_ref_defined_exists.t` | fail | 1 | 10/10 | 0 |
| `src/test/resources/unit/core_subroutine_refs.t` | incomplete | 9 | 22/29 | 0 |
| `src/test/resources/unit/custom_warning_command_line_W.t` | error | 2 | 2/2 | 0 |
| `src/test/resources/unit/data_dump_filter.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/empty_list_assignment_tied_hash_key.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/eval_block_return.t` | fail | 1 | 4/4 | 0 |
| `src/test/resources/unit/eval_unwind_destructor_error.t` | fail | 1 | 2/2 | 2 |
| `src/test/resources/unit/file_temp_path_wrapper_lifetime.t` | fail | 1 | 5/5 | 0 |
| `src/test/resources/unit/foreach_magic_and_prototype_regressions.t` | fail | 4 | 27/27 | 0 |
| `src/test/resources/unit/format_active_lexical.t` | incomplete | 2 | 0/2 | 0 |
| `src/test/resources/unit/glob_hash_argument_slot.t` | fail | 1 | 6/6 | 0 |
| `src/test/resources/unit/global_code_runtime_semantics.t` | fail | 1 | 8/8 | 0 |
| `src/test/resources/unit/goto_foreach_entry_error.t` | fail | 1 | 5/5 | 1 |
| `src/test/resources/unit/hash_autovivification.t` | fail | 3 | 8/8 | 4 |
| `src/test/resources/unit/hash_literal_anonymous_ref_cleanup.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/implicit_argv_readline.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/io_symbolic_select.t` | error | 2 | 2/2 | 0 |
| `src/test/resources/unit/lvalue_sub_explicit_return.t` | fail | 1 | 12/12 | 0 |
| `src/test/resources/unit/named_cv_compiletime_displacement.t` | fail | 1 | 4/4 | 0 |
| `src/test/resources/unit/named_undef_visible_code_slot.t` | fail | 2 | 4/4 | 0 |
| `src/test/resources/unit/namespace_bug_comprehensive.t` | fail | 1 | 6/6 | 1 |
| `src/test/resources/unit/overload/comparison.t` | fail | 1 | 3/0 | 0 |
| `src/test/resources/unit/pvlv_filehandle.t` | fail | 2 | 4/4 | 0 |
| `src/test/resources/unit/refcount/blessed_scalar_reference_contents_destroy.t` | fail | 1 | 4/4 | 0 |
| `src/test/resources/unit/refcount/destroy_anon_containers.t` | fail | 2 | 21/21 | 0 |
| `src/test/resources/unit/refcount/destroy_eval_die.t` | fail | 1 | 10/10 | 0 |
| `src/test/resources/unit/refcount/eval_map_return_cleanup.t` | fail | 1 | 3/3 | 0 |
| `src/test/resources/unit/regex/re_debug_thread_literal_snapshot.t` | error | 1 | 1/1 | 0 |
| `src/test/resources/unit/role_tiny_regressions.t` | fail | 1 | 15/15 | 0 |
| `src/test/resources/unit/scalar_reference_container_lifetime.t` | fail | 2 | 4/4 | 0 |
| `src/test/resources/unit/shadowed_referenced_lexical.t` | error | 0 | 0/0 | 0 |
| `src/test/resources/unit/stash_scalar_ref_assignment.t` | fail | 1 | 4/4 | 0 |
| `src/test/resources/unit/stash_undefined_code_lookup.t` | fail | 2 | 5/5 | 0 |
| `src/test/resources/unit/state.t` | fail | 3 | 17/17 | 0 |
| `src/test/resources/unit/statement.t` | fail | 2 | 24/24 | 0 |
| `src/test/resources/unit/subroutine.t` | fail | 1 | 48/48 | 0 |
| `src/test/resources/unit/substr_lvalue_magical_parent.t` | incomplete | 3 | 3/6 | 0 |
| `src/test/resources/unit/tie_array.t` | fail | 1 | 5/0 | 0 |
| `src/test/resources/unit/tie_void_context.t` | fail | 6 | 8/8 | 0 |
| `src/test/resources/unit/tied_hash_autoviv_refloop_cleanup.t` | fail | 2 | 6/6 | 0 |
| `src/test/resources/unit/undef_typeglob_sub.t` | fail | 1 | 3/3 | 1 |
