use strict;
use warnings;

use Test::More tests => 6;
use PadWalker qw(set_closed_over);

my $old_scalar = 'old scalar';
my @old_array = ('old array');
my %old_hash = (value => 'old hash');
my $closure = sub { return ($old_scalar, $old_array[0], $old_hash{value}) };

my $new_scalar = 'new scalar';
my @new_array = ('new array');
my %new_hash = (value => 'new hash');
set_closed_over(
    $closure,
    {
        '$old_scalar' => \$new_scalar,
        '@old_array'  => \@new_array,
        '%old_hash'   => \%new_hash,
    },
);

is_deeply([$closure->()], ['new scalar', 'new array', 'new hash'],
    'set_closed_over rebinds scalar, array, and hash captures');

$new_scalar = 'changed scalar';
$new_array[0] = 'changed array';
$new_hash{value} = 'changed hash';
is_deeply([$closure->()], ['changed scalar', 'changed array', 'changed hash'],
    'closure aliases observe later changes');

$old_scalar = 'ignored scalar';
$old_array[0] = 'ignored array';
$old_hash{value} = 'ignored hash';
is_deeply([$closure->()], ['changed scalar', 'changed array', 'changed hash'],
    'old lexical containers are detached');

is($new_scalar, 'changed scalar', 'replacement scalar remains directly mutable');
is($new_array[0], 'changed array', 'replacement array remains directly mutable');
is($new_hash{value}, 'changed hash', 'replacement hash remains directly mutable');
