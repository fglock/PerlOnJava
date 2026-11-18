use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 5;

our $DESTROYED = 0;
{
    package ListAssignmentHashTailOwner;
    sub DESTROY { ++$main::DESTROYED }
}

my $old = bless {}, 'ListAssignmentHashTailOwner';
my $old_weak = $old;
weaken($old_weak);
my %tail = (old => $old);
undef $old;
ok(defined $old_weak, 'the original hash element retains its object');

my $replacement = bless {}, 'ListAssignmentHashTailOwner';
my $prefix;
($prefix, %tail) = (1, new => $replacement);
is($prefix, 1, 'scalar LHS receives the first RHS value');
is(scalar keys %tail, 1, 'hash LHS receives the remaining key/value pair');
ok(!defined $old_weak, 'list assignment releases the displaced hash element');
is($DESTROYED, 1, 'the displaced object is destroyed exactly once');
