use strict;
use warnings;
use Test::More;

my $array = [];
my $ok = eval { local @{$array}; 1 };
ok(!$ok, 'localizing through an array reference fails at runtime');
like($@, qr/Can't localize through a reference/,
    'the runtime error is catchable by eval');

done_testing;
