use strict;
use warnings;
use utf8;
use Test::More;

for my $case (
    [ '[z-a]',       'z-a',       '[z-a' ],
    [ '[ネ-a]ネ',    'ネ-a',      '[ネ-a' ],
) {
    my ($pattern, $range, $marked_prefix) = @$case;
    eval { qr/$pattern/ };
    my $error = $@;
    like($error,
        qr/^Invalid \[\] range "\Q$range\E" in regex; marked by <-- HERE in m\/\Q$marked_prefix\E <-- HERE /,
        "$pattern reports the range and marks its end");
}

done_testing;
