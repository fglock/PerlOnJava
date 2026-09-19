use Test::More;

my $ok = eval "use v5.20;\nuse v5.39;\n1";
ok(!$ok, 'second use VERSION is rejected');
like($@, qr/use VERSION of 5\.39 or above is not permitted/, 'reports the high-version conflict');

done_testing;
