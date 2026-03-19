use strict;
use warnings;
use Test::More;

# Test that bare blocks return their last expression value in different contexts
# This is important for modules that end with a bare block containing
# lexical variables (like Package::Stash::PP)

# ============================================================
# VOID context tests - bare blocks should execute without error
# ============================================================

# Test: Bare block in void context
{
    my $executed = 0;
    { $executed = 1; 42; }
    is($executed, 1, 'bare block in void context executes');
}

# Test: Bare block with subroutine call in void context
{
    my $result;
    sub set_result { $result = shift; return 99; }
    { set_result(42); }
    is($result, 42, 'bare block with sub call in void context');
}

# ============================================================
# SCALAR context tests - bare blocks should return last value
# ============================================================

# Test: Simple bare block in scalar context
{
    my $val = do { 42; };
    is($val, 42, 'bare block in scalar context returns value');
}

# Test: Bare block with lexical in scalar context
{
    my $val = do { my $x = 99; $x; };
    is($val, 99, 'bare block with lexical in scalar context');
}

# Test: Bare block with multiple statements in scalar context
{
    my $val = do { my $a = 10; my $b = 20; $a + $b; };
    is($val, 30, 'bare block with multiple statements in scalar context');
}

# Test: Nested bare blocks in scalar context
{
    my $val = do { { { 123; } } };
    is($val, 123, 'nested bare blocks in scalar context');
}

# Test: Bare block with hash in scalar context (Package::Stash::PP pattern)
{
    my $val = do { my %h = (a => 1, b => 2); scalar keys %h; };
    is($val, 2, 'bare block with hash in scalar context');
}

# ============================================================
# LIST context tests - bare blocks should return last value(s)
# ============================================================

# Test: Bare block returning single value in list context
{
    my @vals = do { 42; };
    is_deeply(\@vals, [42], 'bare block returning scalar in list context');
}

# Test: Bare block returning list in list context
{
    my @vals = do { (1, 2, 3); };
    is_deeply(\@vals, [1, 2, 3], 'bare block returning list in list context');
}

# Test: Bare block returning array in list context
{
    my @vals = do { my @arr = (4, 5, 6); @arr; };
    is_deeply(\@vals, [4, 5, 6], 'bare block returning array in list context');
}

# Test: Bare block with hash in list context
{
    my %h = do { (a => 1, b => 2); };
    is_deeply(\%h, {a => 1, b => 2}, 'bare block returning hash pairs in list context');
}

# ============================================================
# RUNTIME context tests (file-level for require/do)
# These test the actual bug fix for Package::Stash::PP
# ============================================================

use File::Temp qw(tempfile);

# TODO: The following tests are for bare block return values in RUNTIME context
# (do "file", require "file"). This feature is not yet fully implemented.
# The fix is complex because changing the context for bare blocks affects
# bytecode generation and can cause ASM stack frame verification failures.
# See cpan_client.md Phase 11a for details.

# Test: Simple bare block return value via do-file
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "{ 42; }\n";
    close $fh;
    my $result = do $filename;
    is($result, 42, 'bare block in file (RUNTIME) returns last expression');
}

# Test: Bare block with lexical variable via do-file
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "{ my \$x = 99; \$x; }\n";
    close $fh;
    my $result = do $filename;
    is($result, 99, 'bare block with lexical in file (RUNTIME)');
}

# Test: Bare block with hash via do-file (Package::Stash::PP pattern)
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "{ my \%h = (a => 1, b => 2); scalar keys \%h; }\n";
    close $fh;
    my $result = do $filename;
    is($result, 2, 'bare block with hash in file (RUNTIME)');
}

# Test: Module ending with bare block returns true for require
# Note: This test has explicit `1;` inside the block, but due to the bare block
# return value issue, the file doesn't return true. Wrap in TODO.
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pm', UNLINK => 1);
    print $fh <<'EOF';
package TestModuleBareBlock;
{
    my %MAP = ('$' => 'SCALAR', '@' => 'ARRAY');
    sub get_type { $MAP{$_[0]} }
    1;  # true value inside block
}
EOF
    close $fh;
    my $result = eval { require $filename };
    if ($@) {
        fail('module with bare block loads successfully (RUNTIME)');
        fail('subroutine in bare block works');
    } else {
        is($result, 1, 'module with bare block loads successfully (RUNTIME)');
        is(TestModuleBareBlock::get_type('@'), 'ARRAY', 'subroutine in bare block works');
    }
}

# Test: Nested bare blocks via do-file
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "{ { { 123; } } }\n";
    close $fh;
    my $result = do $filename;
    is($result, 123, 'nested bare blocks in file (RUNTIME)');
}

# Test: Bare block as last statement after other statements via do-file
TODO: {
    local $TODO = "Bare block return value in RUNTIME context not yet implemented";
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "my \$x = 1; { \$x + 100; }\n";
    close $fh;
    my $result = do $filename;
    is($result, 101, 'bare block as last statement in file (RUNTIME)');
}

# ============================================================
# Labeled block tests - should NOT be affected by the fix
# ============================================================

# Test: Labeled block in void context (Test::More TODO pattern)
{
    my $executed = 0;
    SKIP: { $executed = 1; }
    is($executed, 1, 'labeled block in void context executes');
}

# Test: Labeled block with last
{
    my $count = 0;
    OUTER: {
        $count++;
        last OUTER;
        $count++;
    }
    is($count, 1, 'labeled block with last works correctly');
}

# ============================================================
# Complex bare block tests - Test::More functions inside blocks
# These test the actual problematic patterns
# ============================================================

# Test: Bare block containing Test::More ok() call
{
    my $result;
    { $result = ok(1, 'ok inside bare block'); }
    ok($result, 'bare block with ok() executes correctly');
}

# Test: Bare block containing Test::More is() call
{
    my $result;
    { $result = is(1, 1, 'is inside bare block'); }
    ok($result, 'bare block with is() executes correctly');
}

# Test: Bare block containing is_deeply() call
{
    my $result;
    { $result = is_deeply([1,2], [1,2], 'is_deeply inside bare block'); }
    ok($result, 'bare block with is_deeply() executes correctly');
}

# Test: File with bare block containing function calls
# TODO: This test fails due to stack frame issues when Test::More ok() is called
# inside a bare block in RUNTIME (file) context. The register spilling mechanism
# in For3Node has issues with complex control flow patterns.
{
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh <<'EOF';
use Test::More;
{ ok(1, 'test in file bare block'); 42; }
EOF
    close $fh;
    my $result = do $filename;
    TODO: {
        local $TODO = 'Stack frame issues with Test::More in file-level bare blocks';
        is($result, 42, 'file with bare block containing ok() returns value');
    }
}

# Test: File with ONLY a bare block containing ok() - minimum repro
# TODO: Same issue as above
{
    my ($fh, $filename) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh "use Test::More; { ok(1); }\n";
    close $fh;
    my $result = do $filename;
    TODO: {
        local $TODO = 'Stack frame issues with Test::More in file-level bare blocks';
        ok(defined($result), 'file with bare block ok() returns defined value');
    }
}

done_testing();
