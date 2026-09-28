use strict;
use warnings;
use Test::More;

# The closing bracket after m[..]g belongs to the outer @[ ... ] expression.
my $out = "@{[('A'..'Z')[qq[0020191411140003] =~ m[..]g]]}";
is($out, 'A U T O L O A D', 'interpolation accepts an array closer after m[]g');

done_testing();
