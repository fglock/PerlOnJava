use strict;
use warnings;
use Test::More;

my @cases = (
    [q{x{2147483648}}, q{x{2147483648 <-- HERE }}],
    [q{x{2147483648,}}, q{x{2147483648 <-- HERE ,}}],
    [q{x{2147483648,2147483649}}, q{x{2147483648 <-- HERE ,2147483649}}],
);

for my $case (@cases) {
    my ($pattern, $marked) = @$case;
    eval "#line 1 regex_oversized_quantifier_diagnostics.t\nqr/$pattern/";
    my ($error) = split /\n/, $@;
    like($error,
        qr/^Quantifier in \{,\} bigger than \d+ in regex; marked by <-- HERE in m\/\Q$marked\E\/ at /,
        "oversized quantifier $pattern");
}

done_testing;
