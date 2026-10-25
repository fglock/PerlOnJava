# Parser keyword plugins

PerlOnJava's parser has an internal extension point for compile-time keywords
that produce the same AST consumed by the JVM and interpreter backends. The
current API is intended for bundled language integrations; it is not yet a
stable third-party plugin SDK.

## Plugin contract

Implement `ParserKeywordPlugin` and register it under the keyword text. At a
statement boundary, `parse(Parser parser, String keyword)` receives the live
parser with `parser.tokenIndex` at the keyword. It must consume the tokens that
belong to its declaration and return ordinary AST nodes. A malformed
declaration should use the parser's source-aware diagnostic methods.

`parseExpression` is an optional hook for keywords that can also appear in
expression position. Return `null` when the keyword does not begin an
expression there; the parser then continues with normal identifier handling.
The statement hook is invoked only for a registered keyword and is expected to
return a node or report a parse error.

The interface is defined in
[`ParserKeywordPlugin.java`](../../src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java).
`Parser.registerKeywordPlugin` and `removeKeywordPlugin` update the active
`ScopedSymbolTable`, so registrations follow lexical parser scopes.

## Module providers and lexical scope

`ParserExtensionRegistry` maps a module name to a provider callback. The
statement parser invokes that provider when it compiles `use Module` or
`no Module`; the callback registers or removes its keyword names in the
current lexical scope. Nested blocks and `eval` use the parser's existing
scope and snapshot behavior.

The built-in `Function::Parameters` provider is a small example. It registers
`fun` and `method`, and both hooks lower declarations into the ordinary
subroutine/signature AST. See
[`ParserExtensionRegistry.java`](../../src/main/java/org/perlonjava/frontend/parser/ParserExtensionRegistry.java)
and
[`FunctionParametersKeywordPlugin.java`](../../src/main/java/org/perlonjava/frontend/parser/FunctionParametersKeywordPlugin.java).

Plugins operate on mutable parser state. Preserve the previous value of any
parser mode flags that the plugin changes, restore them in `finally`, and avoid
leaving temporary token edits or scope state behind after parsing. Parser
registration is compile-time and lexical; runtime `import` alone cannot make
new syntax available to source that has already been parsed.
