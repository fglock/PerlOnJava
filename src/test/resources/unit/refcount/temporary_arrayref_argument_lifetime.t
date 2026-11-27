use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @destroyed;

{
    package TemporaryArrayArgument;
    sub new { bless { id => $_[1] }, $_[0] }
    sub DESTROY { push @main::destroyed, $_[0]->{id} }
}

my $consume = sub {
    my @values = @{$_[0]};
    return;
};

my $weak;
{
    my $object = TemporaryArrayArgument->new('temporary');
    $weak = $object;
    weaken($weak);
    $consume->([$object]);
}

ok(!defined($weak), 'object in temporary array argument is released');
is_deeply(\@destroyed, ['temporary'], 'DESTROY runs after the call drops its array argument');

done_testing();
