use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

our $DESTROY_COUNT = 0;

{
    package Issue1649AttributeCapture::Probe;

    sub DESTROY {
        ++$main::DESTROY_COUNT;
    }
}

{
    package Issue1649AttributeCapture;

    sub MODIFY_CODE_ATTRIBUTES {
        return;
    }
}

my $weak;
my $closure;
{
    package Issue1649AttributeCapture;

    my $object = bless {}, 'Issue1649AttributeCapture::Probe';
    $weak = $object;
    Scalar::Util::weaken($weak);
    $closure = sub : PreservePrototype { $object };
}

package main;

ok(defined $weak, 'the returned closure retains its captured value');
undef $closure;
ok(!defined $weak, 'releasing the attributed closure releases its captured pad');
is($DESTROY_COUNT, 1, 'captured object is destroyed exactly once');

done_testing;
