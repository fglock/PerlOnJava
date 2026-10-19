use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

my $object = bless {}, 'ArrayElementIntegerStore::Object';
my $weak = $object;
weaken($weak);
my @array = ($object);
undef $object;

ok(defined $weak, 'array element retains its referent before replacement');
$array[0] = 7;
ok(!defined $weak, 'integer assignment releases the array element referent');

package ArrayElementIntegerStore::Object;
