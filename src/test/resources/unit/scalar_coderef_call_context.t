use strict;
use warnings;
use Test::More tests => 6;

my $count_matches = sub { () = m/(a)/g };

for my $case (
    [ 'apple',      1 ],
    [ 'hello',      0 ],
    [ 'armageddon', 2 ],
) {
    local $_ = $case->[0];
    is scalar $count_matches->($_), $case->[1], "scalar coderef call counts matches in $case->[0]";
}

my @sorted = do {
    my @values = qw(apple hello armageddon);
    my @keys = map { local $_ = $_; scalar $count_matches->($_) } @values;
    @values[sort { $keys[$a] <=> $keys[$b] } 0 .. $#values];
};

is_deeply \@sorted, [qw(hello apple armageddon)], 'scalar coderef results drive numeric sort';

is scalar(sub { return qw(first second) }->()), 'second', 'scalar coderef call returns final list value';
is scalar(sub { return () }->()), undef, 'scalar coderef call returns undef for empty list';
