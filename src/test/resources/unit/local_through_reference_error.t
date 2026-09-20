use strict;
use warnings;
use Test::More;

for my $source (
    q{no strict; local %{\%hash}},
    q{no strict; local @{\@array}},
) {
    my $ok = eval $source;
    ok(!$ok, 'local through a reference fails');
    like($@, qr/Can't localize through a reference/,
        'local through a reference has Perl diagnostic');
}

done_testing;
