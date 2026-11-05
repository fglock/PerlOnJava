use strict;
use warnings;
use Test::More;
use Math::BigInt;
use Scalar::Util::Numeric qw(:all);
use Scalar::Util::LooksLikeNumber qw(looks_like_number);

is(isnum(0), 1, 'zero has the UV flag');
is(isnum(-1), 9, 'negative integer has UV and negative flags');
is(isnum('0.00'), 5, 'decimal string has UV and float flags');
is(isnum('1e3'), 4, 'exponent notation has the float flag');
is(isnum(undef), 0, 'undef is not numeric');
is(isnum('12x'), 0, 'non-numeric string is rejected');
is(isint(-99), -1, 'negative integer returns -1');
is(isint(0), 1, 'zero returns 1');
is(isint('1.0'), 0, 'decimal string is not an integer');
is(isfloat(-3.5), 1, 'negative decimal is a float');
is(isneg(-3.5), 1, 'negative decimal is negative');
is(isuv(-3.5), 1, 'in-range negative decimal has UV flag');
is(isbig(3), 0, 'ordinary integer is not big');
is(isinf('Inf'), 1, 'Inf is recognized');
is(isnan('NaN'), 1, 'NaN is recognized');

my $uvmax = Scalar::Util::Numeric::uvmax();
ok(defined $uvmax, 'uvmax is available');
is(isnum($uvmax), 1, 'UV_MAX remains in the UV range');
my $above_uvmax = Math::BigInt->new($uvmax)->badd(1)->bstr;
is(isnum($above_uvmax), 2, 'integer above UV_MAX has the big flag');
is(isbig($above_uvmax), 1, 'integer above UV_MAX is big');
is(isnum('-' . $above_uvmax), 10, 'negative integer above UV_MAX keeps negative and big flags');

{
    package ScalarUtilNumericStringified;
    use overload '""' => sub { $_[0]->[0] }, fallback => 1;
    sub new { bless [ $_[1] ], $_[0] }
}
is(isnum(ScalarUtilNumericStringified->new(42)), 1,
    'Numeric classifies overloaded stringification');

# The raw looks_like_number API returns grok_number flags for strings, but
# Perl scalar flags for values already held as numbers.
is(looks_like_number('1.5'), 5, 'raw API preserves string grok flags');
is(looks_like_number('1e3'), 4, 'raw API preserves exponent flags');
is(looks_like_number('not a number'), 0, 'raw API returns zero for invalid strings');
is(looks_like_number(1), 4352, 'raw API preserves integer SV flags');
is(looks_like_number(1.5), 8704, 'raw API preserves NV scalar flags');
is(looks_like_number(undef), 0, 'raw API returns zero for undef');
is(looks_like_number([]), 0, 'raw API returns zero for a reference');
is(looks_like_number('Inf'), 20, 'raw API preserves infinity flags');
is(looks_like_number('NaN'), 36, 'raw API preserves NaN flags');

SKIP: {
    skip 'Windows-specific numeric spellings', 4 unless $^O eq 'MSWin32';
    is(isinf('1.#INF'), 1, 'Windows infinity spelling is recognized');
    is(isinf('-1.#INF'), 1, 'negative Windows infinity spelling is recognized');
    is(isnan('1.#IND'), 1, 'Windows NaN spelling is recognized');
    is(isnan('-1.#IND'), 1, 'negative Windows NaN spelling is recognized');
}

done_testing();
