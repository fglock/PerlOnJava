use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 6;

our $destroyed = 0;

sub refcount { svref_2object($_[0])->REFCNT }

my @elements;
my $slot = \$elements[0];
my $object = bless {}, 'ArrayElementScalarReferenceRefcount::Tracked';
my $weak = $object;
weaken($weak);

is(refcount($object), 1, 'the lexical begins as the only strong owner');

$$slot = $object;
is(refcount($object), 2,
    'storing through a scalar reference adds the array element owner');

undef $object;
is(refcount($weak), 1,
    'the array element is the sole strong owner after lexical release');
ok(defined $weak, 'the weak observer remains while the array element owns the object');

undef $elements[0];
ok(!defined $weak, 'clearing the array element clears the weak observer');
is($destroyed, 1, 'clearing the array element runs DESTROY exactly once');

package ArrayElementScalarReferenceRefcount::Tracked;
sub DESTROY { ++$main::destroyed }
