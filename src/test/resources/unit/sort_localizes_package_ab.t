use strict;
use warnings;
use Test::More tests => 4;

our $a = 'saved a';
our $b = 'saved b';

my @sorted = sort { $a cmp $b } qw(zeta alpha gamma);

is_deeply(\@sorted, [qw(alpha gamma zeta)], 'sort comparator sees localized package a and b');
is($a, 'saved a', 'sort restores the package a scalar');
is($b, 'saved b', 'sort restores the package b scalar');

{
    package Local::SortableObject;
    sub new { bless { value => $_[1] }, $_[0] }
}

$a = Local::SortableObject->new('kept');
my @again = sort qw(c b a);
is(ref($a), 'Local::SortableObject', 'sort does not destroy an object stored in package a');
