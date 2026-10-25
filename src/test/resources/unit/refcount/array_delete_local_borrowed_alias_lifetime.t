use strict;
use warnings;
use Test::More;

{
    package ArrayDeleteLocalBorrowedAliasProbe;
    our $DESTROYED = 0;
    sub new { bless {}, shift }
    sub DESTROY { ++$DESTROYED }
}

sub local_delete_middle_argument {
    delete local $_[1];
    is($ArrayDeleteLocalBorrowedAliasProbe::DESTROYED, 0,
       'localized delete preserves the borrowed caller argument');
}

my $left = [];
my $object = ArrayDeleteLocalBorrowedAliasProbe->new;
my $right = [];
local_delete_middle_argument($left, $object, $right);
is($ArrayDeleteLocalBorrowedAliasProbe::DESTROYED, 0,
   'caller still owns object after localized delete returns');
undef $object;
is($ArrayDeleteLocalBorrowedAliasProbe::DESTROYED, 1,
   'caller release destroys localized-delete object');

done_testing;
