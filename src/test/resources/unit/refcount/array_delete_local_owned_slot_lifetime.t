use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package ArrayDeleteLocalOwnedSlotProbe;
    our $DESTROYED = 0;
    sub new { bless {}, shift }
    sub DESTROY { ++$DESTROYED }
}

sub local_delete_middle_array_slot {
    my ($array) = @_;
    my $deleted = delete local $array->[1];
    is($ArrayDeleteLocalOwnedSlotProbe::DESTROYED, 0,
       'localized delete keeps the returned owned element alive');
    undef $deleted;
    is($ArrayDeleteLocalOwnedSlotProbe::DESTROYED, 0,
       'restored array slot retains ownership after result release');
}

my @values = ([], ArrayDeleteLocalOwnedSlotProbe->new, []);
my $weak = $values[1];
weaken($weak);
local_delete_middle_array_slot(\@values);
is(ref($weak), 'ArrayDeleteLocalOwnedSlotProbe',
   'restored owning array slot keeps the object alive');
undef $values[1];
is($ArrayDeleteLocalOwnedSlotProbe::DESTROYED, 1,
   'clearing the restored array slot releases its owner');
ok(!defined($weak), 'weak reference clears after the restored slot is cleared');

done_testing;
