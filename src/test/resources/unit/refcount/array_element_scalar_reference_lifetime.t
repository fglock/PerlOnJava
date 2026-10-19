use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

{
    package ArrayElementScalarReferenceLifetime::Tracked;
    sub DESTROY { }
}

my @elements;
my $slot = \$elements[0];
my $object = bless {}, 'ArrayElementScalarReferenceLifetime::Tracked';
my $weak = $object;
weaken($weak);

$$slot = $object;
undef $object;
undef $slot;

ok(defined $weak,
    'an element assigned through a scalar reference remains owned by its array');

undef $elements[0];
ok(!defined $weak,
    'clearing the array element releases its final referent owner');
