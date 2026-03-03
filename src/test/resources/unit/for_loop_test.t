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

my $x = "original";
my @a = ("a", "b", "c");
foreach $x (@a) { }
is($x, "original", 'foreach restores lexical loop variable');

$x = "before";
foreach $x (1..3) { }
is($x, "before", 'foreach with range restores lexical loop variable');

our $gv = "saved";
foreach $gv ("x", "y") { }
is($gv, "saved", 'foreach restores global loop variable');

# $1 scoping: persists across iterations, restores after loop exit
{
    "abc" =~ /(a)/;
    is($1, 'a', '$1 set before for(;;) loop');
    for (my $i = 0; $i < 2; $i++) {
        "x${i}y" =~ /(\d)/;
    }
    is($1, 'a', '$1 restored after for(;;) loop');
}

{
    my $s = "a1b2c3";
    my @seen;
    for (;;) {
        last unless $s =~ /([a-z])/g;
        push @seen, $1;
        $s =~ /(\d)/g or last;
    }
    is_deeply(\@seen, ['a', 'b', 'c'], '$1 persists across for(;;) iterations');
}

{
    "abc" =~ /(a)/;
    foreach my $i (1..2) {
        "x${i}y" =~ /(\d)/;
    }
    is($1, 'a', '$1 restored after foreach loop');
}

{
    "xyz" =~ /(y)/;
    {
        "abc" =~ /(b)/;
    }
    is($1, 'y', '$1 restored after bare block');
}

# local is restored per-iteration (each iteration is a dynamic scope)
$main::lv = "outer";
for my $i (1..3) {
    local $main::lv = "iter$i";
}
is($main::lv, 'outer', 'local restored after foreach loop');

$main::lv2 = "outer";
for (my $j = 0; $j < 3; $j++) {
    local $main::lv2 = "iter$j";
}
is($main::lv2, 'outer', 'local restored after for(;;) loop');

done_testing();
