use warnings;
use Test::More;

for my $source ('${#}', '${*}', '${"#"}', '${"*"}') {
    my $ok = eval $source;
    my $variable = index($source, '#') >= 0 ? '$#' : '$*';
    ok(!$ok, "$source is rejected");
    like($@, qr/\Q$variable\E is no longer supported as of Perl 5\.30/,
        "$source reports the removed punctuation variable");
}

for my $case (
    ["my(\$a?\$b:\$c)\n", 'Can\'t declare conditional expression in "my"'],
    ["my(do{})\n", 'Can\'t declare do block in "my"'],
) {
    my ($source, $expected) = @$case;
    my $ok = eval $source;
    ok(!$ok, "$source is rejected");
    like($@, qr/\Q$expected\E/, "$source reports its invalid declaration form");
}

done_testing;
