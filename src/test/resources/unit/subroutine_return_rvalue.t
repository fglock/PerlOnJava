use strict;
use warnings;

use Test::More tests => 5;

our $shared;

sub global_key {
    $shared = shift;
    return $shared;
}

isnt(
    global_key('left'),
    global_key('right'),
    'ordinary subroutine returns copy a reused global scalar slot',
);

{
    my $slot;
    sub lexical_key {
        $slot = shift;
        return $slot;
    }
}

isnt(
    lexical_key('first'),
    lexical_key('second'),
    'ordinary subroutine returns copy a captured lexical scalar slot',
);

my $object = bless {}, 'ReturnRvalueObject';
sub return_object { return $object }

is(
    return_object(),
    $object,
    'copying a returned scalar preserves its referent',
);

sub depth_first {
    my ($node) = @_;
    return ($node, map { depth_first($_) } @$node);
}

my $tree = [[[], []], []];
my @depth_first = depth_first($tree);
is(
    scalar(@depth_first),
    5,
    'recursive map returns the complete list in list context',
);

is(
    scalar(depth_first($tree)),
    4,
    'recursive map returns its generated element count in scalar context',
);
