use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our $destroyed = 0;
{
    package Issue1649::LocalizedArraySize;
    sub DESTROY { ++$main::destroyed }
}

my @values;
my $weak;
{
    my $object = bless {}, 'Issue1649::LocalizedArraySize';
    $weak = $object;
    weaken($weak);
    push @values, $object;
    undef $object;

    {
        local $#values = -1;
        is(scalar @values, 0, 'local array length applies its size assignment');
        ok(!defined($weak), 'the removed element releases its referent');
    }

    is(scalar @values, 0, 'local array length is not restored at scope exit');
    ok(!defined($weak), 'scope exit does not restore a dead array element');
    is($destroyed, 1, 'the removed element is destroyed once');
}

done_testing();
