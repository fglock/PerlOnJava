use strict;
use warnings;
use Test::More;

our $observed_context;

sub observed_context {
    $observed_context = !defined(wantarray) ? 'V' : wantarray ? 'A' : 'S';
    return $observed_context;
}

sub or_context { 0 || observed_context() }
sub and_context { 1 && observed_context() }
sub defined_or_context { undef // observed_context() }
sub ternary_middle_context { 1 ? observed_context() : 'unused' }
sub ternary_right_context { 0 ? 'unused' : observed_context() }

my @or_result = or_context();
my @and_result = and_context();
my @defined_or_result = defined_or_context();
my @ternary_middle_result = ternary_middle_context();
my @ternary_right_result = ternary_right_context();

is($or_result[0], 'A', '|| RHS inherits runtime list context');
is($and_result[0], 'A', '&& RHS inherits runtime list context');
is($defined_or_result[0], 'A', '// RHS inherits runtime list context');
is($ternary_middle_result[0], 'A', 'ternary middle branch inherits runtime list context');
is($ternary_right_result[0], 'A', 'ternary right branch inherits runtime list context');

# An empty list assignment imposes list context even when the assignment's
# value is discarded by the enclosing statement.
() = or_context();
is($observed_context, 'A', 'empty list assignment preserves || RHS list context');
() = and_context();
is($observed_context, 'A', 'empty list assignment preserves && RHS list context');
() = defined_or_context();
is($observed_context, 'A', 'empty list assignment preserves // RHS list context');
() = ternary_middle_context();
is($observed_context, 'A', 'empty list assignment preserves ternary middle list context');
() = ternary_right_context();
is($observed_context, 'A', 'empty list assignment preserves ternary right list context');

done_testing;
