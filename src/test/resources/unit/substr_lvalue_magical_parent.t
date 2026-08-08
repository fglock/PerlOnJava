use strict;
use warnings;
use Test::More tests => 6;

our @items;

$#items = -1;
substr($#items, 0, 2) = 23;
is($#items, 23, 'substr assignment writes through $#array');

$#items = -1;
substr($#items, 0, 2) =~ s/\A..\z/23/s;
is($#items, 23, 'substr substitution writes through $#array');

$#items = -1;
substr($#items, 0, 2, 23);
is($#items, 23, 'four-argument substr writes through $#array');

sub last_index :lvalue { $#items }

$#items = -1;
substr(last_index(), 0, 2) = 23;
is($#items, 23, 'substr assignment writes through lvalue subroutine');

$#items = -1;
substr(last_index(), 0, 2) =~ s/\A..\z/23/s;
is($#items, 23, 'substr substitution writes through lvalue subroutine');

$#items = -1;
substr(last_index(), 0, 2, 23);
is($#items, 23, 'four-argument substr writes through lvalue subroutine');
