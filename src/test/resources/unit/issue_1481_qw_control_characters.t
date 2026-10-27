use strict;
use warnings;
use Test::More;

my @leading = qw(  middle);
is_deeply(\@leading, ["\x07", "\x05", "middle"],
    'qw preserves leading literal control characters');

my @trailing = qw(middle  );
is_deeply(\@trailing, ["middle", "\x05", "\x06"],
    'qw preserves trailing literal control characters');

my @ordinary = qw(alpha beta gamma);
is_deeply(\@ordinary, [qw(alpha beta gamma)], 'qw still splits ordinary whitespace');

done_testing;
