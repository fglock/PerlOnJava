use strict;
use warnings;
use Test::More;
use Config ();
use POSIX qw(strtod :limits_h);

my ($number, $unparsed) = strtod('12.5xy');
is($number, 12.5, 'strtod parses the numeric prefix');
is($unparsed, 2, 'strtod reports the count of trailing unparsed characters');

($number, $unparsed) = strtod('-');
is($number, 0, 'strtod returns zero when there is no numeric prefix');
is($unparsed, 1, 'strtod counts the whole input when nothing parses');

($number, $unparsed) = strtod('  -');
is($number, 0, 'strtod still returns zero when whitespace precedes invalid input');
is($unparsed, 3, 'strtod counts the original input when no conversion occurs');

($number, $unparsed) = strtod('1e+');
is($number, 1, 'strtod leaves an incomplete exponent unparsed');
is($unparsed, 2, 'strtod counts every character after the parsed prefix');

($number, $unparsed) = strtod(' 12x');
is($number, 12, 'strtod accepts leading whitespace');
is($unparsed, 1, 'strtod counts the suffix after leading whitespace');

my $longsize = $Config::Config{perlonjava}
    ? ($^O eq 'MSWin32' ? 4 : 8)
    : $Config::Config{longsize};
my $expected_long_max = $longsize >= 8
    ? '9223372036854775807'
    : '2147483647';
is("" . POSIX::LONG_MAX(), $expected_long_max,
    'POSIX::LONG_MAX matches the platform C long width');
is("" . LONG_MAX(), $expected_long_max,
    'LONG_MAX is available through the limits_h export');

done_testing;
