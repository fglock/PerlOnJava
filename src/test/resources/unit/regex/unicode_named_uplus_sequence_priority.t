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
