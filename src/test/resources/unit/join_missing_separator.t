use Test::More;

my $ok = eval 'join,';
ok(!$ok, 'join without a separator is rejected');
like($@, qr/Not enough arguments for join or string/, 'reports join arity');
like($@, qr/near "join,"/, 'reports the join expression location');

done_testing;
