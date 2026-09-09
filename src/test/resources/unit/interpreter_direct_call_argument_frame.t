use strict;
use warnings;
use Test::More tests => 4;

sub rewrite_first {
    $_[0] = 'rewritten';
    return join ':', @_;
}

my $scalar = 'original';
is(rewrite_first($scalar), 'rewritten',
   'ordinary scalar argument is visible through the callee argument frame');
is($scalar, 'rewritten', 'ordinary scalar argument aliases the caller scalar');

my @values = ('left', 'right');
is(rewrite_first(@values), 'rewritten:right',
   'ordinary list argument preserves all callee argument-frame elements');
is($values[0], 'rewritten', 'ordinary list argument aliases its caller element');
