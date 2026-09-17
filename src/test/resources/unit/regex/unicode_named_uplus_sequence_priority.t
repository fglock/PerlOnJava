use strict;
use warnings;
use Test::More;

my @warnings;
my $value;
{
    local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
    $value = eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
        . q!"\N{U+41.42}"!;
}
my ($scalar_error) = split /\n/, $@;
is($scalar_error,
    'Invalid hexadecimal number in \N{U+...} at unicode_named_uplus_sequence_priority.t line 1, within string',
    'dotted U+ form remains invalid in a string');
is(scalar @warnings, 0, 'invalid string form has no preceding warning');

eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
    . q!qr/\N{U+1_0000_0000_0000_0000}/!;
my ($overflow_error) = split /\n/, $@;
is($overflow_error,
    'Use of code point 0x1_0000_0000_0000_0000 is not allowed; the permissible max is 0x7FFFFFFFFFFFFFFF in regex; marked by <-- HERE in m/\\N{U+1_0000_0000_0000_0000 <-- HERE }/ at unicode_named_uplus_sequence_priority.t line 1.',
    'underscored U+ value above signed-UV max reports the range diagnostic');

eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
    . q!qr/\N{U+100.1_0000_0000_0000_0000}/!;
($overflow_error) = split /\n/, $@;
is($overflow_error,
    'Use of code point 0x1_0000_0000_0000_0000 is not allowed; the permissible max is 0x7FFFFFFFFFFFFFFF in regex; marked by <-- HERE in m/\N{U+100.1_0000_0000_0000_0000 <-- HERE }/ at unicode_named_uplus_sequence_priority.t line 1.',
    'a dotted U+ sequence reports overflow in its later component');

my $regex = eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
    . q!qr/\N{U+41.42}/!;
is($@, '', 'dotted U+ sequence is legal in a regex');
is("$regex", q!(?^:\N{U+41.42})!, 'qr stringification preserves dotted U+ source');
ok('AB' =~ $regex, 'dotted U+ sequence matches its code points');

$regex = eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
    . q!qr/[\N{U+0.00}]/!;
is($@, '', 'dotted U+ sequence is legal in a closed class');
is("$regex", q!(?^:[\N{U+0.00}])!, 'closed-class stringification preserves source');

eval "#line 1 unicode_named_uplus_sequence_priority.t\n"
    . q!qr/0000000000000000[\N{U+0.00}0000/!;
my ($priority_error) = split /\n/, $@;
is($priority_error,
    'Unmatched [ in regex; marked by <-- HERE in m/0000000000000000[ <-- HERE \N{U+0.00}0000/ at unicode_named_uplus_sequence_priority.t line 1.',
    'unmatched class diagnostic precedes valid dotted U+ resolution');

done_testing;
