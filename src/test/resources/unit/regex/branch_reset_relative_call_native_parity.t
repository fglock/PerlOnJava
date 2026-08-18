use strict;
use warnings;
use Test::More;

my $regex = eval { qr/(*MARK:joni)((?|(?<a>a)(?-1)|(?<b>b)(?-1)|(?<c>c)(?-1)))/ };
ok(defined($regex), 'branch-reset relative-call pattern compiles') or diag($@);

for my $case (
    ['aa', 'aa'],
    ['bb', 'bb'],
    ['cc', 'cc'],
) {
    my ($input, $capture) = @$case;
    ok($input =~ $regex, "$input uses its lexical branch capture");
    is($1, $capture, "$input preserves the outer capture");
}

for my $input (qw(ab bc ca)) {
    ok($input !~ $regex, "$input rejects a different call target");
}

done_testing;
