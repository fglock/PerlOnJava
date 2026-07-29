use strict;
use warnings;

my $test = 0;
sub check {
    my ($got, $expected, $name) = @_;
    ++$test;
    print(($got == $expected ? "ok" : "not ok"), " $test - $name\n");
    print("# got $got, expected $expected\n") if $got != $expected;
}

sub caller_line {
    return (caller)[2];
}

print "1..5\n";

my $or_line;
my $or_start = __LINE__ + 1;
0
    || ($or_line = caller_line());
check($or_line, $or_start, 'or RHS uses expression start line');

my $and_line;
my $and_start = __LINE__ + 1;
1
    && ($and_line = caller_line());
check($and_line, $and_start, 'and RHS uses expression start line');

my ($nested_left, $nested_right, $nested_sum);
my $nested_start = __LINE__ + 1;
0
    || ($nested_sum = ($nested_left = caller_line())
        + ($nested_right = caller_line()));
check($nested_left, $nested_start, 'nested first call uses outer expression start line');
check($nested_right, $nested_start, 'nested second call uses outer expression start line');

my $defined_or_line;
undef
    // ($defined_or_line = caller_line());
check($defined_or_line, __LINE__ - 1, 'defined-or retains its RHS call line');
