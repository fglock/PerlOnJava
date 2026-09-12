use strict;
use warnings;
use Test::More tests => 2;

sub static_substitution {
    my ($replacement) = @_;
    my $value = 'a-a';
    $value =~ s/a/$replacement/g;
    return $value;
}

is(static_substitution('left'), 'left-left',
    'a static substitution uses its current replacement');
is(static_substitution('right'), 'right-right',
    'a repeated static substitution refreshes its replacement');
