use Test::More;

my $ok = eval 'my $!';
ok(!$ok, 'global-only punctuation variable cannot be lexical');
like($@, qr/Can't use global \$! in "my"/, 'reports the global-only variable');
like($@, qr/near "my \$!/, 'reports the declaration location');

done_testing;
