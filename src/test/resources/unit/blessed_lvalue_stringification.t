use strict;
use warnings;
use Test::More;
use Scalar::Util qw(reftype);

my $value = 'abc';
my $reference = bless \substr($value, 1, 1), 'BlessedLvalueStringification';

is(ref($reference), 'BlessedLvalueStringification', 'blessed lvalue reference retains its class');
is(reftype($reference), 'LVALUE', 'blessed lvalue reference retains its underlying type');
like("$reference", qr/^BlessedLvalueStringification=LVALUE\(0x[0-9a-f]+\)$/, 
    'blessed lvalue reference stringification includes class and LVALUE type');

done_testing;
