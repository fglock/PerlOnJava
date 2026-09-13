use strict;
use warnings;
use Test::More;

my $plain = 42;
is('prefix:' . $plain, 'prefix:42', 'plain integer stringifies in concatenation');

my $negative = -7;
is('prefix:' . $negative, 'prefix:-7', 'negative integer stringifies in concatenation');

my $large = 4_294_967_296;
is('prefix:' . $large, 'prefix:4294967296', 'wide integer stringifies in concatenation');

$_ = 9;
is('prefix:' . $_, 'prefix:9', 'topic variable retains concatenation semantics');

done_testing;
