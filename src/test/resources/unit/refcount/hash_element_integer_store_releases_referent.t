use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

my $object = bless {}, 'HashElementIntegerStore::Object';
my $weak = $object;
weaken($weak);
my %hash = (value => $object);
undef $object;

ok(defined $weak, 'hash entry retains its referent before replacement');
$hash{value} = 7;
ok(!defined $weak, 'integer assignment releases the hash entry referent');

package HashElementIntegerStore::Object;
