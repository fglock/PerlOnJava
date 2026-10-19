use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

our $destroyed = 0;
my $object = bless {}, 'HashElementIntegerStore::Object';
my $weak = $object;
weaken($weak);
my %hash = (value => $object);
undef $object;

ok(defined $weak, 'hash entry retains its referent before replacement');
$hash{value} = 7;
ok(!defined $weak, 'integer assignment releases the hash entry referent');
is($destroyed, 1, 'integer assignment runs the referent destructor once');

package HashElementIntegerStore::Object;
sub DESTROY { ++$main::destroyed }
