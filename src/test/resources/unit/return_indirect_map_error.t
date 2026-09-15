use Test::More;

my $ok = eval q{sub f { return name map { $_ + 1 } 1 .. 5; }};
ok(!$ok, 'return rejects an indirect map argument');
like($@, qr/Missing comma after first argument to return/, 'reports the specific diagnostic');

$ok = eval q{sub g { return if grep $_, @_; } 1;};
ok($ok, 'return statement modifiers remain valid');

done_testing;
