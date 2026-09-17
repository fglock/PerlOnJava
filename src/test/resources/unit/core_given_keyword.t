use strict;
use warnings;
use Test::More;

my $value;
CORE::given(1) { $value = 'entered' }
is($value, 'entered', 'CORE::given is parsed as a core keyword');

done_testing;
