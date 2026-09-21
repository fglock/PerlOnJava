use strict;
use warnings;
use Test::More tests => 1;

my $perl = $^X;
open my $child, '|-', $perl, '-e',
    '$SIG{"INT"} = "DEFAULT"; kill "INT", $$; sleep 1;'
    or die "open pipe: $!";
close $child;

ok(($? & 255) != 0, 'pipe wait status preserves signal termination');
