use strict;
use warnings;
use Test::More tests => 3;

sub unpack_three_arguments {
    my ($first, $second, $third) = @_;
    $_[0] .= '-caller';
    return join ':', map { defined $_ ? $_ : '<undef>' } ($first, $second, $third);
}

my $first = 'one';
is(unpack_three_arguments($first, 'two', 'three'), 'one:two:three',
    'three-slot lexical argument unpack retains values before caller mutation');
is($first, 'one-caller',
    'three-slot lexical argument unpack preserves argument aliasing');
my $only = 'one';
is(unpack_three_arguments($only), 'one:<undef>:<undef>',
    'three-slot lexical argument unpack supplies missing values as undef');
