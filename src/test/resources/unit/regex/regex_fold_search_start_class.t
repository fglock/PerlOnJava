use strict;
use warnings;
use utf8;
use Test::More;

my @cases = (
    [qr/(?i:k)/u,            "\N{KELVIN SIGN}", 'Kelvin literal partition'],
    [qr/(?i:[k])/u,          "\N{KELVIN SIGN}", 'Kelvin class partition'],
    [qr/(?i:\x{e5})/u,      "\N{ANGSTROM SIGN}", 'Angstrom literal partition'],
    [qr/(?i:[\x{e5}])/u,    "\N{ANGSTROM SIGN}", 'Angstrom class partition'],
    [qr/(?i:\x{2c65})/u,    "\x{23a}", 'pinned literal sibling'],
    [qr/(?i:[\x{2c65}])/u,  "\x{23a}", 'pinned class sibling'],
);

for my $case (@cases) {
    my ($regex, $match, $name) = @$case;
    my $subject = ('x' x 4096) . $match;
    ok($subject =~ $regex, "$name remains reachable after a long rejection prefix");
    is($-[0], 4096, "$name begins at the exact character offset");
    ok((('x' x 4096) . 'Q') !~ $regex, "$name negative control");
}

my $global = "\N{KELVIN SIGN}kK\N{KELVIN SIGN}";
my @offsets;
while ($global =~ /(?i:k)/ug) {
    push @offsets, $-[0];
}
is_deeply(\@offsets, [0, 1, 2, 3],
    'fold start-class search preserves global match order');

done_testing;
