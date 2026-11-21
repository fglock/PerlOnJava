use strict;
use warnings;
use Test::More tests => 1;
use Function::Parameters;

my $calls = 0;
fun () { $calls++ }->();

is($calls, 1,
    'anonymous fun expression statements retain an immediately following arrow call');
