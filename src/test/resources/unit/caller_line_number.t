#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 13;
use Eval::Closure qw(eval_closure);
use Sub::Util qw(set_subname);

# Test caller() returns correct line numbers, especially for deeper stack frames.
# This tests the fix for the bug where caller($level) with level > 1 returned
# line numbers near the end of the file instead of the actual call site.

# Test 1: caller(0) inside a function returns the line where the function is called
sub get_caller_0 {
    my @c = caller(0);
    return $c[2];
}
my $expected_line_1 = __LINE__ + 1;
my $result1 = get_caller_0();
is($result1, $expected_line_1, "caller(0) returns line where function is called");

# Test 2: caller(1) - one level up
sub inner_1 { 
    my @c = caller(1); 
    return $c[2]; 
}
sub outer_1 { 
    inner_1();  # This line should be returned by caller(1)
}
my $expected_line_2 = __LINE__ + 1;
my $result2 = outer_1();
is($result2, $expected_line_2, "caller(1) returns correct line one level up");

# Test 3: caller(2) - two levels up (the main bug case)
sub level3_a { 
    my @c = caller(2); 
    return $c[2]; 
}
sub level2_a { level3_a(); }
sub level1_a { level2_a(); }
my $expected_line_3 = __LINE__ + 1;
my $result3 = level1_a();
is($result3, $expected_line_3, "caller(2) returns correct line two levels up");

# Test 4: caller(3) - three levels up
sub d4 { my @c = caller(3); return $c[2]; }
sub d3 { d4(); }
sub d2 { d3(); }
sub d1 { d2(); }
my $expected_line_4 = __LINE__ + 1;
my $result4 = d1();
is($result4, $expected_line_4, "caller(3) returns correct line three levels up");

# Test 5: Different packages (simulating Log4perl scenario)
package Logger;
sub format_line {
    my @c = caller(2);
    return $c[2];
}
sub log_call { format_line(); }

package Wrapper;
sub wrap { Logger::log_call(); }

package main;
my $expected_line_5 = __LINE__ + 1;
my $result5 = Wrapper::wrap();
is($result5, $expected_line_5, "caller(2) correct across different packages");

# Test 6: caller() subroutine name (element 3) should be correct
sub get_caller_sub {
    my @c = caller(1);
    return $c[3];  # Subroutine name
}
sub my_wrapper { get_caller_sub(); }
my $result6 = my_wrapper();
is($result6, "main::my_wrapper", "caller(1) returns correct subroutine name");

# Test 7: Line number should NOT be near end of file
# This specifically tests the bug where end-of-file line was returned
my $approx_file_end = 100;  # This file is about 90 lines
ok($result3 < $approx_file_end - 20, 
   "caller(2) line ($result3) is not near EOF (~$approx_file_end)");

# Test 8: Verify the line numbers are reasonable (not 0 or negative)
ok($result3 > 0 && $result3 < 100, 
   "caller(2) line ($result3) is a reasonable positive number");

sub multiline_direct_caller { return (caller(0))[2]; }
my $expected_line_9 = __LINE__ + 3;
my $result9 = multiline_direct_caller(
    sub { 1 }
);
is($result9, $expected_line_9, "caller(0) reports closing line for multiline direct call");

{
    package CallerLineNumber::Obj;
    sub new { bless {}, shift }
    sub multiline_method_caller { return (caller(0))[2]; }
}

my $expected_line_10 = __LINE__ + 3;
my $result10 = CallerLineNumber::Obj->new->multiline_method_caller(
    sub { 1 }
);
is($result10, $expected_line_10, "caller(0) reports closing line for multiline method call");

my $expected_line_11 = __LINE__ + 3;
eval {
    *{
        CallerLineNumber::Missing::for_line_test()
    };
};
like($@,
     qr/Undefined subroutine &CallerLineNumber::Missing::for_line_test called at .* line $expected_line_11\./,
     "undefined multiline direct call reports function line");

my $renamed_eval_closure = eval_closure(
    source => q{sub { return (caller(0))[3] }},
);
set_subname( 'Other::Renamed', $renamed_eval_closure );
is( $renamed_eval_closure->(),
    'Other::Renamed',
    'caller(0)[3] uses explicit set_subname package for eval closures' );

{
    package CallerLineNumber::GeneratedAccessor;
    sub get {
        return (caller(1))[3];
    }

    my $generated = sub { get() };
    Sub::Util::set_subname('CallerLineNumber::GeneratedAccessor::generated', $generated);
    no strict 'refs';
    *{'CallerLineNumber::GeneratedAccessor::generated'} = $generated;
}

is( CallerLineNumber::GeneratedAccessor::generated(),
    'CallerLineNumber::GeneratedAccessor::generated',
    'caller(1)[3] uses explicit set_subname for generated accessors' );

# End of tests
