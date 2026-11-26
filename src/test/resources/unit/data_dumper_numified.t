use strict;
use warnings;
use Test::More;
use Data::Dumper;
use Scalar::Util qw(dualvar looks_like_number);

local $Data::Dumper::Terse = 1;
local $Data::Dumper::Useqq = 1;

my $untouched = '1556933584';
is(Dumper($untouched), qq("1556933584"\n), 'untouched ten-digit numeric string stays quoted');

my $literal = 1556933584;
is(Dumper($literal), "1556933584\n", 'ten-digit numeric literal dumps as numeric');

my $numified = '1556933584';
my $n = 0 + $numified;
is(Dumper($numified), "1556933584\n", 'ten-digit string used in numeric context dumps as numeric');

my $array_string = '450';
my $array_number = 0 + $array_string;
is(Dumper([$array_string, $array_number]), "[\n  450,\n  450\n]\n",
    'arithmetic exposes numeric flags when the source scalar is dumped in an array');

my $string_dualvar = dualvar(0, 'text');
is(Dumper($string_dualvar), qq("text"\n),
    'a dualvar with distinct numeric and string values keeps its string representation');

my $copied = $numified;
is(Dumper($copied), "1556933584\n", 'assignment preserves numified scalar state');

my $checked = '1556933584';
ok(looks_like_number($checked), 'looks_like_number recognizes numeric string');
is(Dumper($checked), qq("1556933584"\n), 'looks_like_number does not numify scalar');

my $intified = '1556933584';
my $i = int($intified);
is(Dumper($intified), "1556933584\n", 'int() marks ten-digit string as numified');

done_testing;
