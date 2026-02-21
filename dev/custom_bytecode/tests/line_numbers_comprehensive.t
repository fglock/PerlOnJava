#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test 1: Simple die - line 7
eval { die "Line7" };
like($@, qr/Line7 at .* line 7/, 'die on line 7');

# Test 2: Die with blank lines
eval {


    die "Line14";

};
like($@, qr/Line14 at .* line 14/, 'die with blank lines shows line 14');

# Test 3: Die in subroutine
sub level1 {
    die "In level1";
}
eval { level1(); };
like($@, qr/In level1 at/, 'die in subroutine');

# Test 4: Multi-level subroutines
sub level3 { die "Level3" }
sub level2 { level3() }
sub level1_nested { level2() }
eval { level1_nested(); };
like($@, qr/Level3 at/, 'die in nested subroutines');

# Test 5: caller() with no nesting
sub test_caller_0 {
    my ($pkg, $file, $line) = caller(0);
    return ($pkg, $file, $line);
}
my ($p, $f, $l) = test_caller_0();
is($p, 'main', 'caller(0) package');
ok($l > 0, 'caller(0) line number');

# Test 6: caller() with nesting
sub inner_caller {
    my ($pkg, $file, $line) = caller(1);
    return ($pkg, $file, $line);
}
sub outer_caller {
    return inner_caller();
}
my ($p2, $f2, $l2) = outer_caller();
is($p2, 'main', 'caller(1) package');
ok($l2 > 0, 'caller(1) line number');

# Test 7: caller() with blank lines


sub with_blanks {


    my ($pkg, $file, $line) = caller(0);


    return $line;
}


my $line_number = with_blanks();
# This call is on line ~68, with_blanks caller(0) should report this line
ok($line_number > 60 && $line_number < 75, 'caller with blank lines');

done_testing();
