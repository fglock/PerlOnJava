use strict;
use warnings;
use Test::More tests => 15;

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

my $nested_optional_lookbehind =
    qr/b(?=.*(?<=(?<=(.{4}))?(.{5})).$)/;
for my $case (
    ['abcd5678', 'y:undef:cd567'],
    ['abcde5678', 'y:undef:de567'],
    ['abcdef5678', 'y:abcd:ef567'],
    ['abcdefg5678', 'y:bcde:fg567'],
) {
    my ($subject, $expected) = @$case;
    my $matched = $subject =~ $nested_optional_lookbehind;
    is(join(':', $matched ? 'y' : 'n',
            defined($1) ? $1 : 'undef', defined($2) ? $2 : 'undef'),
        $expected,
        "nested optional lookbehind preserves captures for $subject");
}
ok('acde5678' !~ $nested_optional_lookbehind,
    'nested optional lookbehind rejects a subject without its leading literal');
