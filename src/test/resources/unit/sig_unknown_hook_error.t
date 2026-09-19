use Test::More;

my $ok = eval q{$SIG{_HUNGRY} = sub {};};
ok(!$ok, 'unknown signal hook assignment is rejected');
like($@, qr/No such hook: _HUNGRY/, 'reports Perl-compatible hook diagnostic');

$ok = eval q{$SIG{__WARN__} = sub {}; 1;};
ok($ok, 'known Perl hook remains assignable');

$ok = eval { $SIG{"__WARN__\0"} = sub {}; 1; };
ok(!$ok, 'unknown hook with trailing NUL is rejected');
like($@, qr/No such hook: __WARN__\\0/, 'renders trailing NUL visibly in hook diagnostic');

done_testing;
