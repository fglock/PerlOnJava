use strict;
use warnings;
use Test::More tests => 2;

for my $exit_code (1, 2) {
    my $child = '$! = 0; $? = ' . ($exit_code << 8) . '; die;';
    my $output = qx{"$^X" -e '$child' 2>&1};
    is($? >> 8, $exit_code,
        "uncaught die uses exit status $exit_code from \$? when \$! is zero");
}
