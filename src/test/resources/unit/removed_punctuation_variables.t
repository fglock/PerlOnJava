use warnings;
use Test::More;

for my $source ('${#}', '${*}', '${"#"}', '${"*"}') {
    my $ok = eval $source;
    my $variable = index($source, '#') >= 0 ? '$#' : '$*';
    ok(!$ok, "$source is rejected");
    like($@, qr/\Q$variable\E is no longer supported as of Perl 5\.30/,
        "$source reports the removed punctuation variable");
}

done_testing;
