use strict;
use warnings;
use feature 'state';
use Scalar::Util qw(weaken);
use Test::More;
our ($rescued, $weak, $initialized, $code_destroyed, $metadata_destroyed);
our @during_destroy;
($initialized, $code_destroyed, $metadata_destroyed) = (0, 0, 0);
{
    package CodeStateMetadata;
    sub DESTROY { ++$main::metadata_destroyed }
    package ResurrectedStateCode;
    sub DESTROY {
        push @main::during_destroy, defined($main::weak) ? 'live' : 'gone';
        ++$main::code_destroyed;
        $main::rescued = $_[0] if $main::code_destroyed == 1;
    }
}
my $callback = do {
    my $seed = 41;
    sub { state $metadata = bless { serial => ++$initialized, value => $seed }, 'CodeStateMetadata'; $metadata }
};
bless $callback, 'ResurrectedStateCode';
$weak = $callback->(); weaken($weak);
is($initialized, 1, 'state initialized once');
undef $callback;
is($code_destroyed, 1, 'first CODE owner release invokes DESTROY');
ok(defined $weak, 'resurrected CODE retains state');
is($rescued->()->{serial}, 1, 'resurrected CODE uses original state');
is($initialized, 1, 'resurrection does not initialize replacement state');
is($metadata_destroyed, 0, 'state metadata survives first CODE DESTROY');
undef $rescued;
is($code_destroyed, 2, 'last resurrected CODE owner invokes DESTROY again');
is_deeply(\@during_destroy, ['live', 'live'], 'state survives both CODE destructor callbacks');
ok(!defined $weak, 'final CODE destruction releases weak state');
is($metadata_destroyed, 1, 'state metadata destroyed exactly once');
done_testing();
