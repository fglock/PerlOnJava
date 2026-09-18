use Test::More;

eval 'our sub Foo::bar;';
like($@, qr/^No package name allowed for subroutine &Foo::bar in "our" at \(eval \d+\) line 1, near "our sub Foo::bar"\n/,
     'our sub rejects a qualified name');

eval "use feature 'lexical_subs', 'state';\nmy sub Foo::bar;\nstate sub Foo::baz;";
like($@, qr/^"my" subroutine &Foo::bar can't be in a package at \(eval \d+\) line 2, near "[\s\S]*?my sub Foo::bar"\n"state" subroutine &Foo::baz can't be in a package at \(eval \d+\) line 3, near "[\s\S]*?state sub Foo::baz"\n/,
     'my and state sub retain both qualified-name diagnostics');

done_testing;
