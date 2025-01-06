use 5.32.0;
use feature 'say';

# Test multiple variables in for loop with pairs
my @pairs = (1,2, 3,4, 5,6);
my $test_num = 1;
for my ($x, $y) (@pairs) {
    my $expected_x = 2 * $test_num - 1;
    my $expected_y = 2 * $test_num;
    print "not " if $x != $expected_x || $y != $expected_y;
    say "ok $test_num # pair values: got <$x,$y>, expected <$expected_x,$expected_y>";
    $test_num++;
}

# Test with uneven number of elements
my @uneven = (10,20, 30,40, 50);
my @expected = ([10,20], [30,40], [50,undef]);
$test_num = 1;
for my ($a, $b) (@uneven) {
    my ($exp_a, $exp_b) = @{$expected[$test_num-1]};
    if (defined $exp_b) {
        print "not " if $a != $exp_a || $b != $exp_b;
        say "ok # pair $test_num: got <$a,$b>, expected <$exp_a,$exp_b>";
    } else {
        print "not " if $a != $exp_a || defined $b;
        say "ok # incomplete pair $test_num: got <$a,undef>, expected <$exp_a,undef>";
    }
    $test_num++;
}

# Test with array of array references
my @nested = ([1,2], [3,4], [5,6]);
my $pair_num = 1;
for my ($first, $second) (map { @$_ } @nested) {
    my $exp_first = 2 * $pair_num - 1;
    my $exp_second = 2 * $pair_num;
    print "not " if $first != $exp_first || $second != $exp_second;
    say "ok # nested pair $pair_num: got <$first,$second>, expected <$exp_first,$exp_second>";
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
    print "not " if !exists($expected_pairs{$key}) || $expected_pairs{$key} ne $value;
    say "ok # hash entry: got <$key,$value>, expected <$key,$expected_pairs{$key}>";
}

# Test with list of expressions
my @nums = 1..3;
my @squares = map { ($_, $_ * $_) } @nums;
my $idx = 0;
for my ($num, $square) (@squares) {
    my $exp_num = $nums[$idx];
    my $exp_square = $exp_num * $exp_num;
    print "not " if $num != $exp_num || $square != $exp_square;
    say "ok # number and square: got <$num,$square>, expected <$exp_num,$exp_square>";
    $idx++;
}

1;
