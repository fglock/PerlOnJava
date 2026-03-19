#!/usr/bin/env perl
use strict;
use warnings;

# Unit test to document and verify context semantics for different Perl block types
# This test helps understand how PerlOnJava should handle contexts

print "1..12\n";

# Declare this before BEGIN so BEGIN can set it
# Note: Don't initialize with = 0, as that happens at runtime AFTER BEGIN runs
our $begin_ctx;
our $begin_ran;
BEGIN { 
    $begin_ran = 1;
    $begin_ctx = defined(wantarray()) ? (wantarray() ? "LIST" : "SCALAR") : "VOID";
}

sub ctx { 
    my $w = wantarray();
    return defined($w) ? ($w ? "LIST" : "SCALAR") : "VOID";
}

# Test 1: Top-level script context when calling a sub
# Note: The context depends on how the result is used
my $top_ctx = ctx();  # Called in scalar context (assigned to scalar)
print $top_ctx eq "SCALAR" ? "ok 1 - sub called at top-level in scalar assignment sees SCALAR\n" 
                           : "not ok 1 - sub called at top-level in scalar assignment sees SCALAR (got $top_ctx)\n";

# Test 2-4: Subroutine called in different contexts
sub test_sub { return ctx(); }

test_sub();  # void context call
my $void_result = "VOID";  # We can't capture void context result, but sub sees caller's context

my $scalar_ctx = test_sub();
print $scalar_ctx eq "SCALAR" ? "ok 2 - sub called in scalar context sees SCALAR\n"
                              : "not ok 2 - sub called in scalar context sees SCALAR (got $scalar_ctx)\n";

my @list_ctx = test_sub();
print $list_ctx[0] eq "LIST" ? "ok 3 - sub called in list context sees LIST\n"
                             : "not ok 3 - sub called in list context sees LIST (got $list_ctx[0])\n";

# Test 4: Bare block as expression returns its value
my $bare_result = do { 42 };
print $bare_result == 42 ? "ok 4 - bare block as expression returns value\n"
                         : "not ok 4 - bare block as expression returns value (got $bare_result)\n";

# Test 5: Bare block as last statement in sub returns its value
sub sub_with_bare_block { { 99 } }
my $sub_bare = sub_with_bare_block();
print $sub_bare == 99 ? "ok 5 - bare block as last statement in sub returns value\n"
                      : "not ok 5 - bare block as last statement in sub returns value (got $sub_bare)\n";

# Test 6: Nested bare blocks return innermost value
my $nested = do { { { 123 } } };
print $nested == 123 ? "ok 6 - nested bare blocks return innermost value\n"
                     : "not ok 6 - nested bare blocks return innermost value (got $nested)\n";

# Test 7: File loaded via 'do' runs in scalar context and returns last value
my $tmpfile = "/tmp/context_do_test_$$.pl";
open my $fh, '>', $tmpfile or die "Cannot create $tmpfile: $!";
print $fh "{ 456 }\n";
close $fh;
my $do_result = do $tmpfile;
unlink $tmpfile;
print $do_result == 456 ? "ok 7 - do file with bare block returns block value\n"
                        : "not ok 7 - do file with bare block returns block value (got " . ($do_result // "undef") . ")\n";

# Test 8: eval string with bare block returns value
my $eval_result = eval '{ 789 }';
print $eval_result == 789 ? "ok 8 - eval string with bare block returns value\n"
                          : "not ok 8 - eval string with bare block returns value (got $eval_result)\n";

# Test 9: BEGIN block runs in void context
print $begin_ran == 1 ? "ok 9 - BEGIN block executes\n"
                      : "not ok 9 - BEGIN block executes\n";
print $begin_ctx eq "VOID" ? "# BEGIN block context: VOID (as expected)\n"
                           : "# BEGIN block context: $begin_ctx\n";

# Test 10: Bare block with statements before the value
my $multi_stmt = do { my $x = 10; my $y = 20; $x + $y };
print $multi_stmt == 30 ? "ok 10 - bare block returns last expression value\n"
                        : "not ok 10 - bare block returns last expression value (got $multi_stmt)\n";

# Test 11: File ending with VERSION and BEGIN still returns VERSION
my $tmpfile2 = "/tmp/context_version_test_$$.pl";
open my $fh2, '>', $tmpfile2 or die "Cannot create $tmpfile2: $!";
print $fh2 q{
our $VERSION = "1.23";
BEGIN { }
$VERSION;
};
close $fh2;
my $version_result = do $tmpfile2;
unlink $tmpfile2;
print $version_result eq "1.23" ? "ok 11 - file with VERSION and BEGIN returns VERSION\n"
                                : "not ok 11 - file with VERSION and BEGIN returns VERSION (got " . ($version_result // "undef") . ")\n";

# Test 12: File ending with bare block after other statements
my $tmpfile3 = "/tmp/context_mixed_test_$$.pl";
open my $fh3, '>', $tmpfile3 or die "Cannot create $tmpfile3: $!";
print $fh3 q{
my $x = 1;
{ 999 }
};
close $fh3;
my $mixed_result = do $tmpfile3;
unlink $tmpfile3;
print $mixed_result == 999 ? "ok 12 - file ending with bare block returns block value\n"
                           : "not ok 12 - file ending with bare block returns block value (got " . ($mixed_result // "undef") . ")\n";
