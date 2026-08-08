use strict;
use warnings;
use Test::More tests => 1;

my @parts = split(/^/m, "a\nb\nc", 3);
is_deeply(\@parts, ["a\n", "b\n", "c"],
    'initial zero-width match does not consume a split limit slot');
