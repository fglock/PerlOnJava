use strict;
use Test::More;
use feature 'say';

###################
# Perl `pos` Function and `\G` Assertion Tests

# Test `pos` function to get the position of the last match
my $string = "abc def ghi";
my $pattern = qr/(\w+)/;  # Use a capture group to capture the match

# Initial position should be undefined
ok(!(defined pos($string)), 'Initial pos is undefined before matching');

# Perform a global match and check positions
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group instead of {text}
    my $expected_pos = pos($string);
    ok(!($match ne substr($string, $expected_pos - length($match), length($match))), 'Match \'$match\' ends at position $expected_pos');
}

# Reset position and check
pos($string) = 0;
ok(!(pos($string) != 0), 'pos reset to 0');

# Test `\G` to match at the position where the last `m//g` left off
$string = "123 456 789";
$pattern = qr/(\d{3})/;  # Use a capture group

# Match using \G
while ($string =~ /\G$pattern/g) {
    my $match = $1;  # Use the captured group
    ok(!($match !~ /^\d{3}$/), 'Match \'$match\' using \\G');
}

# Test `\G` with intervening non-matching characters
$string = "123 abc 456 def 789";
$pattern = qr/\G(\d{3})/;  # Use a capture group

# Match using \G
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group
    ok(!($match !~ /^\d{3}$/), 'Match \'$match\' using \\G with intervening text');
}

# Test `pos` with manual setting
$string = "abc def ghi";
$pattern = qr/(\w+)/;  # Use a capture group

# manual pos set
pos($string) = 4;
$string =~ /$pattern/g;
my $match = $1;
my $current_pos = pos($string);
ok(!($match ne 'def'), 'Manual pos set to 4, matched \'def\'');

# Test `\G` with alternation
$string = "123abc456";
$pattern = qr/\G((?:\d{3}|abc))/;  # Use a capture group

# Match using \G with alternation
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group
    ok(!($match !~ /^(?:\d{3}|abc)$/), 'Match \'$match\' using \\G with alternation');
}

# Test `pos` after failed match
$string = "abc def ghi";
$pattern = qr/(\d+)/;  # Use a capture group

# Attempt to match digits
$string =~ /$pattern/g;
ok(!(defined pos($string)), 'pos is undefined after failed match');

# non-global match
$string = "123 456";
$pattern = qr/\G(\d{3})/;  # Use a capture group

# Non-global match should not use \G
$string =~ /$pattern/;
ok(!($1 ne '123'), 'Non-global match does not use \\G, matched \'123\'');
###################
# \G anchoring when pos() is undefined
# \G should anchor at position 0 when pos is undef, not scan forward

# \G(\s+) should NOT match "-dac -tac" at pos 0 (no space at pos 0)
my $cfg = "-dac -tac";
if ($cfg =~ /\G(\s+)/gc) {
    ok(0, '\\G(\\s+) should not match when no space at pos 0');
} else {
    ok(1, '\\G(\\s+) correctly fails when pos is undef and no space at pos 0');
}

# \G([a-z]+) should NOT match "-dac -tac" at pos 0 (dash at pos 0)
pos($cfg) = undef;
if ($cfg =~ /\G([a-z]+)/gc) {
    ok(0, '\\G([a-z]+) should not match when no letter at pos 0');
} else {
    ok(1, '\\G([a-z]+) correctly fails when pos is undef and no letter at pos 0');
}

# \G(-) SHOULD match "-dac -tac" at pos 0 (dash at pos 0)
pos($cfg) = undef;
if ($cfg =~ /\G(-)/gc) {
    ok($1 eq '-' && pos($cfg) == 1, '\\G(-) correctly matches dash at pos 0');
} else {
    ok(0, '\\G(-) should match dash at pos 0');
}

# Simulate parse_args pattern: multiple \G/gc alternations on same string
pos($cfg) = undef;
my @tokens;
my $part = "";
while (1) {
    if ($cfg =~ /\G([\"\'])/gc) {
        # quote
    }
    elsif ($cfg =~ /\G(\s+)/gc) {
        push @tokens, $part if length($part);
        $part = "";
    }
    elsif ($cfg =~ /\G(.)/gc) {
        $part .= $1;
    }
    else {
        push @tokens, $part if length($part);
        last;
    }
}
ok(scalar(@tokens) == 2 && $tokens[0] eq '-dac' && $tokens[1] eq '-tac',
   '\\G/gc tokenizer correctly splits "-dac -tac" into two tokens');

###################
# \G in non-/g matches should still anchor at pos()
# This is used by Perl::Tidy's tokenizer for signature detection

my $line = "sub foo(\$bar) { }";
pos($line) = 7;  # after "sub foo"
ok($line =~ /\G\s*\(/, '\\G in non-/g match anchors at pos() - matches ( at pos 7');

pos($line) = 7;
ok(!($line =~ /\Gx/), '\\G in non-/g match anchors at pos() - fails when char does not match');

# \G in non-/g should not change pos()
pos($line) = 7;
$line =~ /\G\s*\(/;
ok(pos($line) == 7, '\\G non-/g match does not change pos()');

# \G with capture in non-/g match
my $data = "hello world";
pos($data) = 6;
if ($data =~ /\G(\w+)/) {
    ok($1 eq 'world', '\\G non-/g match with capture works at pos 6');
} else {
    ok(0, '\\G non-/g match with capture should match at pos 6');
}

###################
# End of Perl `pos` and `\G` Tests

done_testing();
