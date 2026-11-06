use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 5;

our ($weak_capture, $weak_cv);

sub make_cv {
    my $captured = bless {}, 'LocalizedWeakCodeSlot::Probe';
    $weak_capture = $captured;
    weaken($weak_capture);
    my $cv = sub { $captured };
    $weak_cv = $cv;
    weaken($weak_cv);
    return $cv;
}

my $other_owner = make_cv();
{
    no warnings qw(redefine once);
    local *LocalizedWeakCodeSlot::target = $weak_cv;
    ok(defined $weak_capture, 'localized CODE slot retains its captured object');
    ok(LocalizedWeakCodeSlot::target() == $weak_capture,
        'localized CODE remains callable');
}
ok(defined $weak_capture, 'independent strong CODE owner survives glob restore');
ok($other_owner->() == $weak_capture, 'independent CODE owner remains callable');

undef $other_owner;
ok(!defined $weak_capture, 'last CODE owner releases the captured object');

done_testing();
