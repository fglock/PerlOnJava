use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our $DESTROYED = 0;

{
    package Issue1649::EndAggregate;
    sub DESTROY { ++$main::DESTROYED }
}

my @array = (bless {}, 'Issue1649::EndAggregate');
my %hash = (item => bless {}, 'Issue1649::EndAggregate');
my $array_weak = $array[0];
my $hash_weak = $hash{item};
weaken($array_weak);
weaken($hash_weak);

END {
    ok(defined $array_weak, 'queued END retains captured array element');
    ok(defined $hash_weak, 'queued END retains captured hash element');
    is($DESTROYED, 0, 'captured elements survive until END runs');

    @array = ();
    %hash = ();

    ok(!defined $array_weak, 'clearing END array capture releases element');
    ok(!defined $hash_weak, 'clearing END hash capture releases element');
    is($DESTROYED, 2, 'each captured aggregate element is destroyed once');
    done_testing();
}
