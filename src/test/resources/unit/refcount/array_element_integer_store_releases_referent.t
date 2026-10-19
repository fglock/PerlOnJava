use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

our $destroyed = 0;
my $object = bless {}, 'ArrayElementIntegerStore::Object';
my $weak = $object;
weaken($weak);
my @array = ($object);
undef $object;

ok(defined $weak, 'array element retains its referent before replacement');
$array[0] = 7;
ok(!defined $weak, 'integer assignment releases the array element referent');
is($destroyed, 1, 'integer assignment runs the referent destructor once');

package ArrayElementIntegerStore::Object;
sub DESTROY { ++$main::destroyed }
