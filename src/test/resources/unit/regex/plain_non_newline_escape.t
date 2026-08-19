use strict;
use warnings;
use utf8;
use Test::More;

like('a', qr/^\N$/, 'plain N matches an ordinary character');
unlike("\n", qr/^\N$/, 'plain N excludes line feed');
like("\r", qr/^\N$/, 'plain N includes carriage return');
like("\x{2028}", qr/^\N$/, 'plain N includes Unicode line separator');
unlike("\n", qr/^\N$/s, 'dotall does not broaden plain N');

like('ab', qr/^\N{2}$/, 'numeric braces quantify plain N');
unlike('a', qr/^\N{2}$/, 'exact plain N quantifier enforces its lower bound');
like('abc', qr/^\N{2,3}$/, 'bounded plain N quantifier accepts its upper bound');
unlike('abcd', qr/^\N{2,3}$/, 'bounded plain N quantifier rejects longer input');
like('abcd', qr/^\N{2,}$/, 'open plain N quantifier remains unbounded');
like('ab', qr/^\N { 2 }$/x, 'extended mode permits spacing before the quantifier');

like('\\N', qr/^\\N$/, 'escaped backslash leaves literal N syntax inert');
like('A', qr/^\N{LATIN CAPITAL LETTER A}$/,
    'named character syntax remains distinct from plain N');

for my $source ('qr/[\\N]/', 'qr/[\\N{2}]/') {
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $value = eval $source;
    ok(!defined $value, "$source is rejected inside a character class");
    like($@, qr/^\\N in a character class must be a named character: \\N\{\.\.\.\}/,
        "$source reports Perl's class diagnostic");
    is(scalar @warnings, 0, "$source emits no warning before the fatal");
}

done_testing;
