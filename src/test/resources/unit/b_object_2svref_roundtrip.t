use strict;
use warnings;

use B ();
use Scalar::Util qw(refaddr reftype);
use Test::More tests => 5;

my $object = bless {}, 'Local::B::RoundTrip';
my $string = "$object";

like($string, qr/=HASH\(0x([0-9a-fA-F]+)\)\z/, 'object string exposes a reference address');
my ($address) = $string =~ /0x([0-9a-fA-F]+)/;
my $numeric_address = do { no warnings 'portable'; hex $address };
my $b_object = bless \$numeric_address, 'B::SV';
my $round_trip = $b_object->object_2svref;

is(reftype($round_trip), 'HASH', 'object_2svref restores the referent type');
is(ref($round_trip), 'Local::B::RoundTrip', 'object_2svref restores the blessing');
is(refaddr($round_trip), refaddr($object), 'object_2svref restores the same referent');

my $refaddr_value = refaddr($object);
my $refaddr_b_object = bless \$refaddr_value, 'B::SV';
is(refaddr($refaddr_b_object->object_2svref), refaddr($object), 'object_2svref resolves a Scalar::Util::refaddr value');
