use v5.38;
use feature 'signatures';
# no warnings 'experimental::signatures';

sub generate_numbers($start, $limit, $factor) {
    my @nums;
    my $val = $start;
    while (abs($val) <= $limit) {
        push @nums, 0.0 + $val;
        $val *= $factor;
    }
    return @nums;
}

my $inf = 1E100**1E100;
my $nan = $inf/$inf;

# Generate positive and negative values
my @positive = (
    $inf, $nan,
    0.0, 1.0, 2.0,
    1e15, 1e-4,
    generate_numbers(0.0000000000000000000000000000000000001234556788999964433355, 1e50, 234.5678),
);  # 0.1, 1.0, 10.0, ...
my @negative = map { -$_ } @positive;

say for (@positive, @negative);
