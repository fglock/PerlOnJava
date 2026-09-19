use Test::More;

for my $keyword (qw(when default)) {
    my $ok = eval "use v5.10; $keyword" . ($keyword eq 'when' ? '(undef)' : '') . '{}';
    ok(!$ok, "$keyword outside given is rejected");
    like($@, qr/Can't \"$keyword\" outside a topicalizer/, "$keyword has Perl-compatible diagnostic");
}

my $ok = eval q{use v5.10; no warnings 'experimental::smartmatch'; given (1) { when (1) {} default {} } 1;};
ok($ok, 'when and default remain valid inside given');

done_testing;
