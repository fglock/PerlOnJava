use 5.32.0;
use Test::More;
use feature 'say';

# Test multiple variables in for loop with pairs
my @pairs = (1,2, 3,4, 5,6);
my $test_num = 1;
for my ($x, $y) (@pairs) {
    my $expected_x = 2 * $test_num - 1;
    my $expected_y = 2 * $test_num;
    ok($x == $expected_x && $y == $expected_y, "pair values $test_num: got <$x,$y>, expected <$expected_x,$expected_y>");
    $test_num++;
}

# Test with uneven number of elements
my @uneven = (10,20, 30,40, 50);
my @expected = ([10,20], [30,40], [50,undef]);
my $idx = 0;
for my ($a, $b) (@uneven) {
    my ($exp_a, $exp_b) = @{$expected[$idx]};
    if (defined $exp_b) {
        ok($a == $exp_a && $b == $exp_b, "pair " . ($idx+1) . ": got <$a,$b>, expected <$exp_a,$exp_b>");
    } else {
        ok($a == $exp_a && !defined $b, "incomplete pair " . ($idx+1) . ": got <$a,undef>, expected <$exp_a,undef>");
    }
    $idx++;
}

# Test with array of array references
my @nested = ([1,2], [3,4], [5,6]);
my $pair_num = 1;
for my ($first, $second) (map { @$_ } @nested) {
    my $exp_first = 2 * $pair_num - 1;
    my $exp_second = 2 * $pair_num;
    ok(!($first != $exp_first || $second != $exp_second), 'nested pair $pair_num: got <$first,$second>, expected <$exp_first,$exp_second>');
    $pair_num++;
}

# Test with hash entries
my %hash = (
    key1 => 'val1',
    key2 => 'val2',
    key3 => 'val3'
);
my %expected_pairs = (
    key1 => 'val1',
    key2 => 'val2',
    key3 => 'val3'
);
for my ($key, $value) (%hash) {
    ok(exists($expected_pairs{$key}) && $expected_pairs{$key} eq $value, "hash entry: got <$key,$value>, expected <$key,$expected_pairs{$key}>");
}

# Test with list of expressions
my @nums = 1..3;
my @squares = map { ($_, $_ * $_) } @nums;
my $idx2 = 0;
for my ($num, $square) (@squares) {
    my $exp_num = $nums[$idx2];
    my $exp_square = $exp_num * $exp_num;
    ok($num == $exp_num && $square == $exp_square, "number and square: got <$num,$square>, expected <$exp_num,$exp_square>");
    $idx2++;
}

1;

done_testing();
