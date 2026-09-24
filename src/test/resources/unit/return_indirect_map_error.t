use Test::More;

my $ok = eval q{sub f { return name map { $_ + 1 } 1 .. 5; }};
ok(!$ok, 'return rejects an indirect map argument');
like($@, qr/Missing comma after first argument to return/, 'reports the specific diagnostic');

$ok = eval q{sub g { return if grep $_, @_; } 1;};
ok($ok, 'return statement modifiers remain valid');

$ok = eval q{sub h { return sort grep { $_ } qw(b a); } 1;};
ok($ok, 'return accepts a sort grep pipeline');

$ok = eval q{
    sub i {
        my $ns = 'UnitReturnSortMap';
        return sort map { /^(.+)::$/ ? "${ns}::$1" : () } keys %{"${ns}::"};
    }
    1;
};
ok($ok, 'return accepts Mojolicious sort map pipeline');

done_testing;
