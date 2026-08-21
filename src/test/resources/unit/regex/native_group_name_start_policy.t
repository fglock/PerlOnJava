use strict;
use warnings;
use utf8;
use Test::More;

for my $name ('_name', 'éname', 'Ⅳname') {
    my $pattern = "(?<$name>x)";
    my $compiled = eval { qr/$pattern/ };
    ok($compiled, "$name is a valid Perl group name start")
        or diag($@);
}

for my $name ('‿name', "\x{301}name") {
    my $pattern = "(?<$name>x)";
    my $compiled = eval { qr/$pattern/ };
    ok(!$compiled, "$name is not a valid Perl group name start");
    like($@, qr/^Group name must start with a non-digit word character in regex;/,
        "$name reports the Perl group-name diagnostic");
}

done_testing;
