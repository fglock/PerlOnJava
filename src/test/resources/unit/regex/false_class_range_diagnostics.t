use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 4;

for my $case (
    [ '(?[[a-\d]])',  'a-\d'  ],
    [ '(?[[\w-x]])',  '\w-'    ],
    [ '(?[[a-\pM]])', 'a-\pM' ],
    [ '(?[[\pM-x]])', '\pM-'   ],
) {
    my ($pattern, $range) = @$case;
    eval "qr/$pattern/";
    like($@, qr/^False \[\] range "\Q$range\E" in regex; marked by <-- HERE/,
        "$pattern reports its false class range");
}
