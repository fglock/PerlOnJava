use Test::More tests => 1;

BEGIN { $^H{charnames} = \"foo" }
eval q{"\N{a}"};
like $@, qr/^Not a CODE reference at /,
    'reference-valued non-code charnames hint is rejected';
