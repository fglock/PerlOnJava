use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 4;

sub compile_error {
    my ($source) = @_;
    eval { qr/$source/ };
    return $@;
}

my @cases = (
    ['/(?[(\\c]) /', qr/^Syntax error in \(\?\[\.\.\.\]\) in regex/],
    ['(?[(\\c])', qr/^Syntax error in \(\?\[\.\.\.\]\) in regex/],
    ['(?[ ! ! (\\w])', qr/^Unmatched \( in regex/],
    ['(?[ ! ( ! (\\w)])', qr/^Unmatched \( in regex/],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    like(compile_error($source), $expected,
        "$source preserves extended-set failure precedence");
}
