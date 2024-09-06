###################
# Range Operator

my @range = (1..5);
print "not " if join(",", @range) ne "1,2,3,4,5"; say "ok # Range 1..5";


###################
# Ternary Operator

my $a = 10;
my $b = $a > 5 ? "Greater" : "Less";
print "not " if $b ne "Greater"; say "ok # Ternary operator test";


###################
# Autoincrement and Autodecrement

$a = 5;
$a++;
print "not " if $a != 6; say "ok # Autoincrement test";

$a--;
print "not " if $a != 5; say "ok # Autodecrement test";


###################
# File Test Operators

open(my $fh, ">", "testfile.txt");
print $fh "Test content";
close($fh);

# Test if file exists
my $file_exists = -e "testfile.txt";
print "not " if !$file_exists; say "ok # File exists test";

# Test if file is readable
my $file_readable = -r "testfile.txt";
print "not " if !$file_readable; say "ok # File readable test";

# Test if file is writable
my $file_writable = -w "testfile.txt";
print "not " if !$file_writable; say "ok # File writable test";

# Cleanup
unlink "testfile.txt";


###################
# Scalar vs List Context

my @arr = (1, 2, 3);
my $count = @arr;  # In scalar context, returns the number of elements
print "not " if $count != 3; say "ok # Scalar context on array";

