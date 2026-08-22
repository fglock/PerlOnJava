# Modified Joni distribution

This directory is a PerlOnJava-maintained fork of Joni 2.2.7, imported from
upstream tag `joni-2.2.7` at commit
`57fd57b4f977813a7b4b35e0179943b1f06f51d7`.

PerlOnJava modifications include match-time callouts and dynamic properties;
Perl-compatible parsing, diagnostics, case folding, Unicode properties and
boundaries; recursion, control verbs, backtracking, optimizer/compiler/matcher
changes; debug/provenance APIs; generated Unicode tables; and their tests. New
files in this directory that implement or test those changes are maintained by
the PerlOnJava project.

Joni remains licensed under its original MIT License. All upstream copyright,
license, and authorship notices are retained. PerlOnJava's modifications do not
replace or narrow those notices.

Upstream project: https://github.com/jruby/joni
Upstream license: `LICENSE`

Joni uses JCodings 1.0.64 from upstream tag `jcodings-1.0.64`, commit
`996ae0f72c5cc6bb28ef92f29bbd5ef0f63d5250`. PerlOnJava resolves JCodings as
an unmodified binary dependency and retains its license at
`../licenses/jcodings-LICENSE.txt`.
