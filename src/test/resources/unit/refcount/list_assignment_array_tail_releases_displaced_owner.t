use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 5;

our $DESTROYED = 0;
{
    package ListAssignmentArrayTailOwner;
    sub DESTROY { ++$main::DESTROYED }
}

my $old = bless {}, 'ListAssignmentArrayTailOwner';
my $old_weak = $old;
weaken($old_weak);
my @tail = ($old);
undef $old;
ok(defined $old_weak, 'the original array element retains its object');

my $replacement = bless {}, 'ListAssignmentArrayTailOwner';
my $prefix;
($prefix, @tail) = (1, $replacement);
is($prefix, 1, 'scalar LHS receives the first RHS value');
is(scalar @tail, 1, 'array LHS receives the remaining RHS value');
ok(!defined $old_weak, 'list assignment releases the displaced array element');
is($DESTROYED, 1, 'the displaced object is destroyed exactly once');
