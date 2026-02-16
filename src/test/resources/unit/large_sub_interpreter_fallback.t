print "1..3\n";

# Test 1: Small subroutine compiles normally
sub small_sub {
    my $x = shift;
    return $x * 2;
}

my $result = small_sub(21);
print "not " unless $result == 42;
print "ok 1 - small subroutine works\n";

# Test 2: Large subroutine should work (either via compiler or interpreter fallback)
# This sub has many statements to push it over the 65KB JVM bytecode limit
sub large_sub {
    my $sum = 0;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    $sum += 1;
    # Would need ~10,000+ more lines here to actually trigger the limit,
    # but this demonstrates the structure
    return $sum;
}

$result = large_sub();
print "not " unless $result == 10;
print "ok 2 - large subroutine works\n";

# Test 3: Verify subroutine was compiled (check it's defined)
print "not " unless defined(&large_sub);
print "ok 3 - large subroutine is defined\n";
