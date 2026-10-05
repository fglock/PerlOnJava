use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package ShiftedArrayWeakOwner;
    sub new { bless {}, $_[0] }
}

my $weak;
{
    my $strong = ShiftedArrayWeakOwner->new;
    my @queue = ($strong);
    $weak = $strong;
    weaken($weak);

    undef $strong;
    my $shifted = shift @queue;
    ok(defined($weak), 'shifted scalar retains the referent');
    is(ref($shifted), 'ShiftedArrayWeakOwner', 'shift returns the queued referent');

    undef $shifted;
    ok(!defined($weak), 'weak observer clears after the shifted owner is released');
}

done_testing();
