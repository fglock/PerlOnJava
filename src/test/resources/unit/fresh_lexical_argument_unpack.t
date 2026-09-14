use strict;
use warnings;
use Test::More tests => 4;

sub unpack_arguments {
    my ($first, $second) = @_;
    return join ':', map { defined $_ ? $_ : '<undef>' } $first, $second;
}

is(unpack_arguments('first', 'second'), 'first:second',
    'fresh lexical argument unpack keeps both values');
is(unpack_arguments('first'), 'first:<undef>',
    'fresh lexical argument unpack supplies undef for a missing value');

my $left = 'left';
my $right = 'right';
is(unpack_arguments($left, $right), 'left:right',
    'fresh lexical argument unpack copies ordinary caller scalars');
is("$left:$right", 'left:right',
    'argument unpack does not modify caller scalars');
