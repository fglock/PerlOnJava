use strict;
use warnings;
use Test::More;

our %hash;
my $ok = eval qq{#line 1 "local-reference-test"\n\\local %{\\%hash}; 1};
ok(!$ok, 'localizing through a reference fails');
like($@, qr/Can't localize through a reference at local-reference-test line 1\./,
    'the runtime error retains the logical source line');

done_testing;
