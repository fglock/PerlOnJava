use strict;
use warnings;
use Test::More;

for my $pattern ('(?=a\\Ka)a', '(?<=a\\Kb)c') {
    my $ok = eval { qr/$pattern/; 1 };
    ok(!$ok, 'KEEP within a lookaround is rejected');
    like($@,
        qr/\\K not permitted in lookahead\/lookbehind in regex; marked by <-- HERE in m\/.*\\K <-- HERE /,
        'KEEP lookaround diagnostic marks immediately after KEEP');
}

done_testing;
