#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

no strict 'vars';

for my $marker (map { $_ x 7 } qw(< = >)) {
    for my $source (
        $marker,
        "\$_\n$marker",
        "\n\$_ =\n$marker",
    ) {
        eval $source;
        like $@, qr/^Version control conflict marker at \(eval \d+\) line \d+, near "\Q$marker\E"/,
            "conflict marker $marker is diagnosed";
    }
}

done_testing;
