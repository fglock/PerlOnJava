use strict;
use warnings;
use Test::More;

ok(UNIVERSAL->can('import'), 'UNIVERSAL has import');
ok(UNIVERSAL->can('unimport'), 'UNIVERSAL has unimport');
is(eval { UNIVERSAL->import; 1 }, 1, 'empty UNIVERSAL import succeeds');
my $ok = eval { UNIVERSAL->import('can'); 1 };
ok(!$ok, 'UNIVERSAL cannot export a requested symbol');
like($@, qr/^UNIVERSAL does not export anything/, 'UNIVERSAL import error matches Perl');

done_testing;
