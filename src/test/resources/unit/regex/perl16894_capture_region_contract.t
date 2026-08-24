use strict;
use warnings;
use Test::More;

# Perl 5.37.10 changed repeated capture lifetime to retain only captures from
# the final successful iteration. PerlOnJava targets 5.44; the development
# system Perl is 5.34 and therefore cannot serve as the target-version oracle.
plan skip_all => 'requires Perl 5.37.10 final-iteration capture semantics'
    if $] < 5.037010;
plan tests => 33;

{
    my $input = 'abab';
    ok($input =~ /(?:[^b]*(?=(b)|(a))ab)*/, 'original structural family matches');
    is($&, 'abab', 'whole match spans both repetitions');
    ok(!defined($1), 'left lookahead capture omitted by final iteration is cleared');
    is($2, 'a', 'right lookahead capture survives from its successful repetition');
    is_deeply([@-], [0, undef, 2], 'cleared capture has no start offset');
    is_deeply([@+], [4, undef, 3], 'cleared capture has no end offset');

    ok($input !~ /does-not-match/, 'follow-up match fails');
    is($&, 'abab', 'failed follow-up preserves whole match');
    ok(!defined($1), 'failed follow-up preserves cleared left capture state');
    is($2, 'a', 'failed follow-up preserves right capture');
    is_deeply([@-], [0, undef, 2], 'failed follow-up preserves capture starts');
    is_deeply([@+], [4, undef, 3], 'failed follow-up preserves capture ends');
}

{
    my $input = 'ababab';
    ok($input =~ /(?:[^b]*(?=(b)|(a))ab)+/, 'additional repetition matches');
    is($&, 'ababab', 'additional repetition extends whole match');
    ok(!defined($1), 'left capture omitted by final additional iteration is cleared');
    is($2, 'a', 'right capture advances with additional repetition');
    is_deeply([@-], [0, undef, 4], 'additional repetition capture starts are exact');
    is_deeply([@+], [6, undef, 5], 'additional repetition capture ends are exact');
}

{
    my $input = 'abab';
    ok($input =~ /\A(?:(?:[^b]*(?=(b)|(a))ab)*)\z/,
        'nearby wrapped and anchored source matches generically');
    ok(!defined($1), 'nearby source clears omitted left capture');
    is($2, 'a', 'nearby source retains right capture');
    is_deeply([@-], [0, undef, 2], 'nearby source has generic capture starts');
    is_deeply([@+], [4, undef, 3], 'nearby source has generic capture ends');
}

{
    my $input = 'abab';
    ok($input =~ /(?:[^b]*(?=(a)|(b))ab)*/, 'swapped alternation matches');
    is($1, 'a', 'swapped alternation retains first branch capture');
    ok(!defined($2), 'swapped alternation clears omitted second branch capture');
    is_deeply([@-], [0, 2], 'swapped alternation capture starts are exact');
    is_deeply([@+], [4, 3, undef], 'swapped alternation capture ends are exact');
}

{
    my $input = 'bb';
    ok($input =~ /(?:b*(?=(b)|(a))b)+/, 'left-branch control matches');
    is($1, 'b', 'left-branch control captures b');
    ok(!defined($2), 'untaken right branch remains undefined');
    is_deeply([@-, @+], [0, 1, 2, 2, undef],
        'untaken control branch has no region');
    ok('zz' !~ /\A(?:[^b]*(?=(b)|(a))ab)+\z/,
        'negative structural control does not match');
}
