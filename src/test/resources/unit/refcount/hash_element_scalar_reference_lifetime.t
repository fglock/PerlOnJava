use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

our $destroyed = 0;

my %elements;
my $slot = \$elements{entry};
my $object = bless {}, 'HashElementScalarReferenceLifetime::Tracked';
my $weak = $object;
weaken($weak);

$$slot = $object;
undef $object;
undef $slot;

ok(defined $weak,
    'a hash element assigned through a scalar reference remains owned by its hash');

undef $elements{entry};
ok(!defined $weak,
    'clearing the hash element releases its final referent owner');
is($destroyed, 1,
    'clearing the hash element runs the referent destructor exactly once');

package HashElementScalarReferenceLifetime::Tracked;
sub DESTROY { ++$main::destroyed }
