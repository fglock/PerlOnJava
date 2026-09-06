use strict;
use warnings;
use Test::More;

sub TIESCALAR { bless [] }

my $value = 'readonly';
Internals::SvREADONLY($value, 1);
my $ok = eval { tie $value, 'main'; 1 };

ok(!$ok, 'tie cannot replace magic on a runtime-readonly scalar');
like($@, qr/Modification of a read-only value attempted/,
    'tie reports the standard readonly diagnostic');

done_testing;
