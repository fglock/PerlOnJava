# Regression: version->parse("v5.8") is dotted-decimal (tuple 5, 8), not decimal 5.800.
# CPAN FindBin-libs Makefile.PL uses version->parse("v5.8")->numify when sorting
# version/*/ directories; mis-parsing breaks configure and yields empty t/.
#
# Run with system Perl or PerlOnJava:
#   perl src/test/resources/unit/version_dotted_decimal_vs_decimal.t
#   ./jperl src/test/resources/unit/version_dotted_decimal_vs_decimal.t

use strict;
use warnings;
use Test::More;
use version;

# v-prefix string: each segment is an integer (Perl dotted-decimal).
is(
    version->parse('v5.8')->normal,
    'v5.8.0',
    q{v5.8 dotted-decimal normalizes like Perl (v5.8.0 not v5.800.0)},
);

is(
    version->parse('v5.8')->numify,
    '5.008000',
    q{v5.8 numify pads the second segment to three decimals (5.008000)},
);

is(
    version->parse('v5.40')->numify,
    '5.040000',
    q{v5.40 numify is 5.040000 (segment 40, not 5.400000)},
);

# Decimal-style without leading v: minor digits are grouped by threes.
is(
    version->parse('5.8')->normal,
    'v5.800.0',
    q{decimal 5.8 is not the same tuple as v5.8},
);

# numify string differs between Perls (e.g. 5.800 vs 5.800000); numeric value matches.
cmp_ok(
    version->parse('5.8')->numify,
    '==',
    5.8,
    q{decimal 5.8 numifies to the same numeric value as 5.8 on all Perls},
);

cmp_ok(
    version->parse('v5.8')->numify,
    '<',
    version->parse('v5.40')->numify,
    q{v5.8 sorts before v5.40 when comparing numify (FindBin-libs dir scan)},
);

done_testing();
