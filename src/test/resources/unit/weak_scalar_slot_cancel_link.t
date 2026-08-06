use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(isweak weaken);

sub link_cancel_target {
    my ($source, $target) = @_;

    push @{ $source->{on_cancel} }, $target;
    push @{ $target->{revoke_when_ready} },
        my $link = [ $source, \$source->{on_cancel}[-1] ];
    weaken($link->[0]);
    weaken($link->[1]);
}

my $source = {};
my $target = {};
link_cancel_target($source, $target);

ok defined($target->{revoke_when_ready}[0][1]),
    'weak reference to a live aggregate scalar slot survives its creating scope';
ok isweak($target->{revoke_when_ready}[0][1]),
    'aggregate scalar-slot reference remains weak';

my $slot = $target->{revoke_when_ready}[0][1];
undef $$slot;

ok !defined($source->{on_cancel}[0]),
    'dereferencing the weak scalar-slot reference updates the aggregate';
is scalar(@{ $source->{on_cancel} }), 1,
    'undefining the slot preserves the aggregate shape';
