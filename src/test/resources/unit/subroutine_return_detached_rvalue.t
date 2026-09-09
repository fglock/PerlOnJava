use strict;
use warnings;

use Test::More tests => 4;

sub literal_result { return 'literal' }

my $first_literal = literal_result();
pos($first_literal) = 2;
my $second_literal = literal_result();
ok(!defined pos($second_literal),
   'separate literal returns do not share pos storage');

sub computed_result {
    my ($left, $right) = @_;
    return $left . $right;
}

my $first_computed = computed_result('left', 'right');
$first_computed .= '!';
is($first_computed, 'leftright!', 'returned computed temporary remains writable');
is(computed_result('left', 'right'), 'leftright',
   'mutating one returned temporary does not affect a later call');

my @source = ('stored');
sub stored_result { return $source[0] }
my $returned_stored = stored_result();
$returned_stored .= '!';
is($source[0], 'stored', 'returning a stored scalar remains an rvalue copy');
