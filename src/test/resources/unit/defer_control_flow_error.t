use Test::More;
use feature 'defer';
no warnings 'experimental::defer';

my $ok = eval 'defer { return 1 }';
ok(!$ok, 'return from defer is rejected');
like($@, qr/Can't "return" out of a "defer" block/, 'reports the defer control-flow error');
unlike($@, qr/, near /, 'does not add source excerpt to the clean error');

done_testing;
