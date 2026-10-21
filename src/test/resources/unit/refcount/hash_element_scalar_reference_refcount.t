use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 6;

our $destroyed = 0;

sub refcount { svref_2object($_[0])->REFCNT }

my %elements;
my $slot = \$elements{entry};
my $object = bless {}, 'HashElementScalarReferenceRefcount::Tracked';
my $weak = $object;
weaken($weak);

is(refcount($object), 1, 'the lexical begins as the only strong owner');

$$slot = $object;
is(refcount($object), 2,
    'storing through a scalar reference adds the hash element owner');

undef $object;
is(refcount($weak), 1,
    'the hash element is the sole strong owner after lexical release');
ok(defined $weak, 'the weak observer remains while the hash entry owns the object');

undef $elements{entry};
ok(!defined $weak, 'deleting the hash entry clears the weak observer');
is($destroyed, 1, 'deleting the hash entry runs DESTROY exactly once');

package HashElementScalarReferenceRefcount::Tracked;
sub DESTROY { ++$main::destroyed }
