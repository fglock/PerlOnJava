use strict;
use warnings;
use Test::More;

{
    package ArrayDeleteBorrowedAliasProbe;
    our $DESTROYED = 0;
    sub new { bless {}, shift }
    sub DESTROY { ++$DESTROYED }
}

sub delete_middle_argument {
    delete $_[1];
    is($ArrayDeleteBorrowedAliasProbe::DESTROYED, 0,
       'deleting a borrowed middle argument does not release its caller owner');
}

my $left = [];
my $object = ArrayDeleteBorrowedAliasProbe->new;
my $right = [];
delete_middle_argument($left, $object, $right);
is($ArrayDeleteBorrowedAliasProbe::DESTROYED, 0,
   'caller still owns object after the subroutine returns');
undef $object;
is($ArrayDeleteBorrowedAliasProbe::DESTROYED, 1,
   'object is destroyed when its caller owner is released');

done_testing;
