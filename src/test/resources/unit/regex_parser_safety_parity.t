use strict;
use warnings;
use Test::More tests => 11;

for my $case (
    [ '\\k', qr/Sequence \\k\.\.\. not terminated/ ],
    [ '\\kx', qr/Sequence \\k\.\.\. not terminated/ ],
    [ '\\k<', qr/Sequence \\k<\.\.\. not terminated/ ],
    [ "\\k'", qr/Sequence \\k'\.\.\. not terminated/ ],
    [ '\\k{', qr/Sequence \\k\{\.\.\. not terminated/ ],
) {
    my $error = '';
    eval { qr/$case->[0]/ };
    $error = $@;
    like($error, $case->[1], "$case->[0] is rejected deterministically");
}

ok('xx' =~ /(?&word)(?<word>x)/, 'native named forward call matches');
ok('xx' =~ /(?P>word)(?<word>x)/, 'native Python-style forward call matches');
ok('xx' =~ /(?<word>x)\k{word}/, 'brace named backreference matches');

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= $_[0] };
    eval q{ qr{(?&empty){0}abc(?<empty>)} };
}
like($warning, qr/Quantifier unexpected on zero-length expression/,
    'zero-length named call interval warns');

for my $pattern ('((?+18446744073709551615))',
                 '((?-18446744073709551615))') {
    eval { qr/$pattern/ };
    like($@, qr/Invalid reference to group/, 'oversized relative call is bounded');
}
