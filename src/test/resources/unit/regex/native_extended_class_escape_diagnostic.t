use strict;
use warnings;
use Test::More;

sub compile_error {
    my ($pattern) = @_;
    local $@;
    eval { qr/$pattern/ };
    return $@;
}

for my $escape ('8', 'y', 'z') {
    my $pattern = "(?[ \\$escape ])";
    my $error = compile_error($pattern);
    like($error, qr/^Unrecognized escape \\\Q$escape\E in character class in regex;/,
        "extended class reports unrecognized \\$escape");
}

for my $pattern ('(?[ \\n ])', '(?[ [\\]] ])') {
    my $compiled = eval { qr/$pattern/ };
    ok($compiled, "$pattern retains its recognized escape")
        or diag($@);
}

done_testing;
