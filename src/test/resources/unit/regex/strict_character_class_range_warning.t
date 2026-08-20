use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 15;

sub compile_warnings {
    my ($source) = @_;
    my @warnings;
    my $compiled;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $compiled = eval "qr/$source/";
    }
    ok($compiled, "$source compiles");
    return @warnings;
}

my @cases = (
    ['(?[ [ A-a ] ])',
     qr/Ranges of ASCII printables should be some subset of "0-9", "A-Z", or "a-z".*A-a\s+<-- HERE/s],
    ["(?[ [ \x{1a89}-\x{1a90} ] ])",
     qr/Ranges of digits should be from the same group of 10.*\x{1a89}-\x{1a90}\s+<-- HERE/s],
    ['(?[ [ \N{U+00}-\x01 ] ])',
     qr/Both or neither range ends should be Unicode.*\\N\{U\+00\}-\\x01\s+<-- HERE/s],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    my @warnings = compile_warnings($source);
    is(scalar @warnings, 1, "$source emits one range warning");
    like($warnings[0], $expected, "$source reports the warning at its endpoint");
}

for my $source ('(?[ [ A-Z ] ])', '(?[ [ % - % ] ])',
                "(?[ [ \x{1a80}-\x{1a89} ] ])") {
    my @warnings = compile_warnings($source);
    is(scalar @warnings, 0, "$source is a canonical quiet range");
}
