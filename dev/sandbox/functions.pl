###################
# Perl Function Tests

# Test rand
my $random_number = rand();
print "not " if $random_number < 0 || $random_number >= 1; say "ok # rand() returned a value between 0 and 1";

$random_number = rand(100);
print "not " if $random_number < 0 || $random_number >= 100; say "ok # rand(100) returned a value between 0 and 100";

# Test int
my $integer = int(4.75);
print "not " if $integer != 4; say "ok # int(4.75) equals 4";

$integer = int(-4.75);
print "not " if $integer != -4; say "ok # int(-4.75) equals -4";

# Test abs
my $absolute = abs(-42);
print "not " if $absolute != 42; say "ok # abs(-42) equals 42";

$absolute = abs(42);
print "not " if $absolute != 42; say "ok # abs(42) equals 42";

# Test sqrt
my $sqrt_value = sqrt(16);
print "not " if $sqrt_value != 4; say "ok # sqrt(16) equals 4";

$sqrt_value = sqrt(25);
print "not " if $sqrt_value != 5; say "ok # sqrt(25) equals 5";

# Test chomp
my $string = "hello\n";
my $chomped = chomp($string);
print "not " if $chomped != 1 || $string ne "hello"; say "ok # chomp() removed newline";

# Test chop
$string = "hello";
my $chopped = chop($string);
print "not " if $chopped ne 'o' || $string ne "hell"; say "ok # chop() removed last character 'o'";

# Test length
$string = "hello";
my $length = length($string);
print "not " if $length != 5; say "ok # length() of 'hello' is 5";

# Test substr
$string = "hello world";
my $substring = substr($string, 0, 5);
print "not " if $substring ne 'hello'; say "ok # substr() extracted 'hello'";

$substring = substr($string, 6, 5);
print "not " if $substring ne 'world'; say "ok # substr() extracted 'world'";

# Test index
my $position = index($string, "world");
print "not " if $position != 6; say "ok # index() found 'world' at position 6";

$position = index($string, "lo");
print "not " if $position != 3; say "ok # index() found 'lo' at position 3";

# Test lc
$string = "HELLO";
my $lowercase = lc($string);
print "not " if $lowercase ne 'hello'; say "ok # lc() converted 'HELLO' to 'hello'";

# Test uc
$string = "hello";
my $uppercase = uc($string);
print "not " if $uppercase ne 'HELLO'; say "ok # uc() converted 'hello' to 'HELLO'";

# Test ucfirst
$string = "hello";
my $ucfirst = ucfirst($string);
print "not " if $ucfirst ne 'Hello'; say "ok # ucfirst() capitalized 'hello' to 'Hello'";

# Test lcfirst
$string = "Hello";
my $lcfirst = lcfirst($string);
print "not " if $lcfirst ne 'hello'; say "ok # lcfirst() decapitalized 'Hello' to 'hello'";

# Test reverse
my @array = (1, 2, 3);
my @reversed_array = reverse @array;
print "not " if "@reversed_array" ne '3 2 1'; say "ok # reverse() reversed the array";

$string = "hello";
my $reversed_string = reverse $string;
print "not " if $reversed_string ne 'olleh'; say "ok # reverse() reversed the string";

# Test join
my $joined_string = join("-", @array);
print "not " if $joined_string ne '1-2-3'; say "ok # join() joined array into '1-2-3'";

# Test split
my @split_array = split("-", $joined_string);
print "not " if "@split_array" ne '1 2 3'; say "ok # split() split '1-2-3' into array";

# Test push
push(@array, 4);
print "not " if "@array" ne '1 2 3 4'; say "ok # push() added 4 to array";

# Test pop
my $popped = pop(@array);
print "not " if $popped != 4 || "@array" ne '1 2 3'; say "ok # pop() removed 4 from array";

# Test shift
my $shifted = shift(@array);
print "not " if $shifted != 1 || "@array" ne '2 3'; say "ok # shift() removed 1 from array";

# Test unshift
unshift(@array, 0);
print "not " if "@array" ne '0 2 3'; say "ok # unshift() added 0 to array";

# Test grep
@array = (1, 2, 3, 4, 5);
my @even_numbers = grep { $_ % 2 == 0 } @array;
print "not " if "@even_numbers" ne '2 4'; say "ok # grep() filtered even numbers";

# Test map
my @squared_numbers = map { $_ * $_ } @array;
print "not " if "@squared_numbers" ne '1 4 9 16 25'; say "ok # map() squared the numbers";

# Test sort
my @unsorted = (5, 2, 3, 1, 4);
my @sorted = sort { $a <=> $b } @unsorted;
print "not " if "@sorted" ne '1 2 3 4 5'; say "ok # sort() sorted the numbers";

# Test pack and unpack
$string = pack("C*", 72, 101, 108, 108, 111);
print "not " if $string ne 'Hello'; say "ok # pack() packed the string 'Hello'";

@array = unpack("C*", $string);
print "not " if "@array" ne '72 101 108 108 111'; say "ok # unpack() unpacked the string 'Hello'";

# Test sprintf
$string = sprintf("The number is: %d", 42);
print "not " if $string ne 'The number is: 42'; say "ok # sprintf() formatted the string 'The number is: 42'";

# Test localtime
my $time = localtime(0);
print "not " if $time !~ /1970/; say "ok # localtime() returned the correct time for epoch 0";

# Test time
my $current_time = time();
print "not " if $current_time < 0; say "ok # time() returned a positive number";

