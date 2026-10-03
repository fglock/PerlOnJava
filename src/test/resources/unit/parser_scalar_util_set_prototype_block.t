use strict;
use warnings;
use Test::More;
use Scalar::Util qw(set_prototype);

my $code = set_prototype { $_[0] } q{};
is(ref($code), 'CODE', 'set_prototype accepts a block as its code reference');
is($code->('ok'), 'ok', 'the block remains callable after setting its prototype');

done_testing;
