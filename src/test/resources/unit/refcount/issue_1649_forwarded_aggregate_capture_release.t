use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our $destroyed = 0;
{
    package Issue1649::ForwardAggregate;
    sub DESTROY { $main::destroyed++ }
}

my $weak;
{
    my @items = (1);
    my $object = bless \@items, 'Issue1649::ForwardAggregate';
    $weak = $object;
    weaken($weak);

    sub issue1649_forwarded_aggregate;
    sub issue1649_forwarded_aggregate { scalar @items }

    undef $object;
}

ok(defined $weak, 'forward-defined named sub retains its captured aggregate');
is(issue1649_forwarded_aggregate(), 1,
    'forward-defined sub reads the captured aggregate');

undef &issue1649_forwarded_aggregate;
ok(!defined $weak, 'removing the forwarded sub releases its aggregate capture');
is($destroyed, 1, 'captured blessed aggregate is destroyed once');

done_testing;
