use strict;
use warnings;
use Test::More tests => 5;
use Scalar::Util qw(weaken);

our $saved_callback;
our $destroyed = 0;

{
    package Issue1649::ArgumentOwner;
    sub DESTROY { $main::destroyed++ }
}

sub retain_and_call {
    my ($callback) = @_;
    my $captured = $callback->();
    ok(defined $captured, 'temporary callback can read its capture in the callee');
    $saved_callback = $callback;
}

my $weak;
{
    my $object = bless {}, 'Issue1649::ArgumentOwner';
    weaken($weak = $object);
    retain_and_call(sub { $object });
    ok(defined $weak, 'the saved callback retains its capture after caller scope exit');
    is($destroyed, 0, 'the object is not destroyed while the callback is saved');
}

undef $saved_callback;
ok(!defined $weak, 'releasing the saved callback releases its captured object');
is($destroyed, 1, 'the captured object is destroyed exactly once');
