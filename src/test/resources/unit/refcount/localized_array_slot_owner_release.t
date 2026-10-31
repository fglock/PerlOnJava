use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package LocalizedArraySlotOwner;
    our $destroyed = 0;

    sub DESTROY { ++$destroyed }
}

my $weak;
{
    my @values;
    {
        local $values[2];
        my $value = bless {}, 'LocalizedArraySlotOwner';
        $weak = $value;
        weaken($weak);
        $values[2] = $value;
        ok(defined($weak), 'localized array slot owns its assigned reference');
    }

    ok(!defined($weak), 'unwinding a new localized array slot releases its reference');
}

sub check_localized_array_removal {
    my ($operation, $index) = @_;
    my $weak;
    {
        my @values;
        local $values[$index];
        my $value = bless {}, 'LocalizedArraySlotOwner';
        $weak = $value;
        weaken($weak);
        $values[$index] = $value;
        undef $value;

        my $removed = $operation eq 'pop' ? pop @values
                    : $operation eq 'shift' ? shift @values
                    : delete $values[$index];
        ok(defined($weak), "$operation keeps its returned value alive");
        ok(defined($removed), "$operation returns the localized value");
        undef $removed;
        ok(!defined($weak), "$operation releases the removed array owner");
    }
}

check_localized_array_removal('pop', 0);
check_localized_array_removal('shift', 0);
check_localized_array_removal('delete', 1);

is($LocalizedArraySlotOwner::destroyed, 4,
   'localized slot removal dispatches DESTROY exactly once per value');

done_testing();
