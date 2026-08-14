use strict;
use warnings;
use Test::More tests => 5;
use Scalar::Util qw(refaddr);
use B ();

my $target = [];
my $string = "$target";
like($string, qr/0x([0-9a-fA-F]+)/, 'reference string exposes an address');

my ($hex) = $string =~ /0x([0-9a-fA-F]+)/;
my $address = do { no warnings 'portable'; hex $hex };
my $b_sv = bless \$address, 'B::SV';
my $resolved = $b_sv->object_2svref;

is(ref($resolved), 'ARRAY', 'raw B::SV address resolves to the referent');
is(refaddr($resolved), refaddr($target), 'resolved reference preserves identity');

my $object = bless {}, 'Local::TypeObject';
my $object_address = refaddr($object);
my $object_b_sv = bless \$object_address, 'B::SV';
my $resolved_object = $object_b_sv->object_2svref;
is(ref($resolved_object), 'Local::TypeObject',
    'addresses obtained through refaddr resolve blessed hashes');
is(refaddr($resolved_object), refaddr($object),
    'refaddr-based B lookup preserves object identity');
