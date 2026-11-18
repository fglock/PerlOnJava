use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

our @DESTROYED;

{
    package Issue1649LocalizedCodeCapture::Probe;

    sub DESTROY {
        push @main::DESTROYED, 'destroyed';
    }
}

my $weak;
{
    no warnings qw(redefine once);
    my $object = bless {}, 'Issue1649LocalizedCodeCapture::Probe';
    $weak = $object;
    weaken($weak);

    local *Issue1649LocalizedCodeCapture::installed = sub { $object };
    ok(defined $weak, 'localized CODE keeps its captured value alive');
}

ok(!defined $weak, 'restoring the glob releases its final CODE capture');
is_deeply(\@DESTROYED, ['destroyed'], 'captured value is destroyed exactly once');

done_testing;
