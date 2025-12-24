/**
 * Package for refactoring large AST structures to avoid JVM method size limits.
 * <p>
 * The JVM has a hard limit of 65535 bytes per method. Large Perl code blocks and
 * literals can exceed this limit when compiled to Java bytecode. This package
 * provides utilities to automatically split large structures into smaller chunks
 * wrapped in closures.
 * <p>
 * <b>Key Components:</b>
 * <ul>
 *   <li>{@link org.perlonjava.codegen.refactor.NodeListRefactorer} - Unified refactorer for any List&lt;Node&gt;</li>
 *   <li>{@link org.perlonjava.codegen.refactor.ControlFlowValidator} - Validates control flow safety</li>
 *   <li>{@link org.perlonjava.codegen.refactor.RefactoringStrategy} - Strategy interface for different refactoring approaches</li>
 *   <li>{@link org.perlonjava.codegen.refactor.RecursiveBlockRefactorer} - Recursively refactors inner blocks</li>
 * </ul>
 * <p>
 * <b>Activation:</b> Set environment variable {@code JPERL_LARGECODE=refactor} to enable.
 * <p>
 * <b>Control Flow Safety:</b> Control flow statements (next/last/redo/goto) cannot be
 * wrapped in closures because closures create a new scope, breaking the loop context.
 * The refactorer validates control flow and fails safely when refactoring would break semantics.
 *
 * @see org.perlonjava.codegen.refactor.NodeListRefactorer
 * @see org.perlonjava.codegen.refactor.ControlFlowValidator
 */
package org.perlonjava.codegen.refactor;
