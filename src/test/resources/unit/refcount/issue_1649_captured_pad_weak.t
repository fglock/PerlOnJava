use strict;
use warnings;
use Scalar::Util qw(weaken unweaken);
use Test::More;

our $destroyed = 0;
{
    package Issue1649CapturedPadWeak;
    sub DESTROY { $main::destroyed++ }
}

my ($weak_capture, $strong_capture);
{
    my $object = bless {}, 'Issue1649CapturedPadWeak';
    my $referent = $object;
    weaken($referent);
    $weak_capture = sub { $referent };
    undef $object;
}
ok(!defined $weak_capture->(), 'a captured weak scalar does not own its referent');
is($destroyed, 1, 'weak-only referent is destroyed at scope exit');
$weak_capture = undef;

$destroyed = 0;
{
    my $object = bless {}, 'Issue1649CapturedPadWeak';
    my $referent = $object;
    my $closure = sub { $referent };
    weaken($referent);
    unweaken($referent);
    $strong_capture = $closure;
    undef $object;
}
ok(defined $strong_capture->(), 'an unweakened captured scalar keeps its referent');
is($destroyed, 0, 'unweakened referent remains alive after its scope exits');
$strong_capture = undef;
is($destroyed, 1, 'dropping the final closure releases its captured referent');

done_testing;
