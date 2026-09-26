use strict;
use warnings;
use Test::More tests => 1;

sub caller_location {
    my (undef, $file, $line) = caller;
    return "$file:$line";
}

eval q{
# line 42 "inside-eval"
1;
};

like(caller_location(), qr{\Q@{[__FILE__]}\E:\d+$},
    'a #line directive inside q{} does not remap following caller locations');
