#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

subtest 'Parentheses around single expression' => sub {
    my $str = "XXXYYY";
    my $count = ($str =~ tr/X/X/);
    is($count, 3, 'tr/// in parentheses returns count');
    is($str, "XXXYYY", 'Original string unchanged');
};

subtest 'Comma operator in scalar context' => sub {
    my $str1 = "XXXYYY";
    my $str2 = "ZZZAAA";
    my $result = ($str1 =~ tr/X/X/, $str2 =~ tr/Z/Z/);
    is($result, 3, 'Comma operator returns last value (count from second tr///)');
    is($str1, "XXXYYY", 'First string unchanged');
    is($str2, "ZZZAAA", 'Second string unchanged');
};

subtest 'List assignment creates list context' => sub {
    my $str = "XXXYYY";
    my ($count) = $str =~ tr/X/X/;
    is($count, 3, 'tr/// in list context still returns count');
    is($str, "XXXYYY", 'Original string unchanged');
};

subtest 'Context propagation with wantarray' => sub {
    sub context_test {
        return wantarray ? "LIST" : defined wantarray ? "SCALAR" : "VOID";
    }
    
    my $ctx1 = (context_test());
    is($ctx1, "SCALAR", 'Parentheses alone don\'t create list context');
    
    my $ctx2 = (context_test(), context_test());
    is($ctx2, "SCALAR", 'Comma operator in scalar context evaluates operands in SCALAR context');
    
    my ($ctx3) = context_test();
    is($ctx3, "LIST", 'List assignment creates list context');
};

subtest 'Complex context propagation' => sub {
    sub get_context_and_value {
        my $val = shift;
        my $ctx = wantarray ? "LIST" : defined wantarray ? "SCALAR" : "VOID";
        return "$val:$ctx";
    }
    
    my $result1 = (get_context_and_value("A"));
    is($result1, "A:SCALAR", 'Single expression in parens gets scalar context');
    
    my $result2 = (get_context_and_value("A"), get_context_and_value("B"));
    is($result2, "B:SCALAR", 'Comma operator in scalar context: all operands get SCALAR context');
    
    my @results = (get_context_and_value("A"), get_context_and_value("B"));
    is_deeply(\@results, ["A:LIST", "B:LIST"], 'Array assignment: all get list context');
};

subtest 'tr/// behavior in various contexts' => sub {
    my $str = "XXXYYY";
    
    # Direct scalar context
    my $c1 = $str =~ tr/Y/Y/;
    is($c1, 3, 'Direct scalar context returns count');
    
    # Reset string
    $str = "XXXYYY";
    
    # Parentheses (should still be scalar context)
    my $c2 = ($str =~ tr/Y/Y/);
    is($c2, 3, 'Parentheses: still scalar context, returns count');
    
    # Reset string
    $str = "XXXYYY";
    
    # List assignment (creates list context)
    my ($c3) = $str =~ tr/Y/Y/;
    is($c3, 3, 'List assignment: returns count');
    
    # All three should be equal
    is($c1, $c2, 'Direct and parentheses give same result');
    is($c2, $c3, 'Parentheses and list assignment give same result');
};

subtest 'tr/// with /r modifier' => sub {
    my $original = "XXXYYY";
    
    # Without /r - modifies original
    my $str1 = $original;
    my $count1 = $str1 =~ tr/X/A/;
    is($count1, 3, 'Without /r returns count');
    is($str1, "AAAYYY", 'Original string modified');
    
    # With /r - returns new string
    my $str2 = $original;
    my $new_str = $str2 =~ tr/X/A/r;
    is($new_str, "AAAYYY", 'With /r returns modified string');
    is($str2, "XXXYYY", 'Original string unchanged with /r');
};

subtest 'Edge cases and special situations' => sub {
    # Empty string
    my $empty = "";
    my $count_empty = ($empty =~ tr/X/X/);
    is($count_empty, 0, 'Empty string returns 0');
    
    # No matches
    my $no_match = "AAABBB";
    my $count_no_match = ($no_match =~ tr/X/X/);
    is($count_no_match, 0, 'No matches returns 0');
    
    # All matches
    my $all_match = "XXXXX";
    my $count_all = ($all_match =~ tr/X/X/);
    is($count_all, 5, 'All characters match returns correct count');
};

done_testing();

