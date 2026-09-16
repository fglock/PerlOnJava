use Test::More;

my $ok = eval q{$SIG{_HUNGRY} = sub {};};
ok(!$ok, 'unknown signal hook assignment is rejected');
like($@, qr/No such hook: _HUNGRY/, 'reports Perl-compatible hook diagnostic');

$ok = eval q{$SIG{__WARN__} = sub {}; 1;};
ok($ok, 'known Perl hook remains assignable');

done_testing;
