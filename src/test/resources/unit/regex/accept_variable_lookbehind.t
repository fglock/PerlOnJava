use strict;
use warnings;
use Test::More tests => 10;

for my $case (
    [cblrph => 'c'],
    [dblrph => 'd'],
    [xggggblrph => 'x'],
) {
    my ($input, $capture) = @$case;
    ok($input =~ /(?<=([cd](*ACCEPT)|x)gggg)blrph/,
        "$input matches through positive lookbehind");
    is("$&-$1", "blrph-$capture",
        "$input keeps the accepted match and capture values");
}

ok('ab' =~ /(?<=a(?=b(*ACCEPT)))b/,
    'a nested assertion keeps its own ACCEPT boundary');
is($&, 'b', 'nested assertion ACCEPT preserves the outer lookbehind width');

ok('abc' =~ /(?<=ab)c/, 'ordinary fixed lookbehind remains available');
ok('aaabc' =~ /(?<=a{1,3}b)c/,
    'ordinary compound variable lookbehind remains available');
