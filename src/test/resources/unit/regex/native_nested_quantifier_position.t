use strict;
use warnings;
use Test::More;

sub compile_error {
    my ($pattern) = @_;
    local $@;
    eval { qr/$pattern/ };
    return $@;
}

for my $case (
    [ 'x**',    'x** <-- HERE ' ],
    [ "\x{30cd}**\x{30cd}", "\x{30cd}** <-- HERE \x{30cd}" ],
) {
    my ($pattern, $marked) = @$case;
    my $error = compile_error($pattern);
    like($error, qr/^Nested quantifiers in regex;/,
        "$pattern uses the Perl nested-quantifier diagnostic");
    like($error, qr/\Q$marked\E/,
        "$pattern marks immediately after the second quantifier");
}

done_testing;
