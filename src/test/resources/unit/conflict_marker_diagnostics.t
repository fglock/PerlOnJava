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

    eval "# comment with fake << HEREDOC and quote \\\"\n$marker\n";
    like $@, qr/Version control conflict marker.*\Q$marker\E/s,
        "conflict marker after a comment is diagnosed";

    for my $source (
        "my \$text = <<'END';\n$marker\nEND\n",
        "my \$text = \"before\n$marker\nafter\";\n",
        "my \$text = '$marker';\n",
        "my \$text = \"$marker\";\n",
    ) {
        eval $source;
        is $@, '', "marker $marker remains literal string content";
    }
}

eval "<<<<<<< ours\nmy \$x;\n=======\nmy \$y;\n>>>>>>> theirs\n";
like $@, qr{\AVersion control conflict marker at \(eval \d+\) line 1, near "<<<<<<<"\nVersion control conflict marker at \(eval \d+\) line 3, near "======="\nVersion control conflict marker at \(eval \d+\) line 5, near ">>>>>>>"\n\z},
    'all conflict markers are diagnosed in source order';

done_testing;
