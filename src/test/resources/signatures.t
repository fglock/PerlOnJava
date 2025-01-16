use v5.36;

###################
# Perl 5 Signatures Tests

# Empty signature
sub no_args () { 
    return 42;
}
print "not " if no_args() != 42;
say "ok # Empty signature";

# Basic mandatory parameters
sub add ($left, $right) {
    return $left + $right;
}
print "not " if add(2, 3) != 5;
say "ok # Basic mandatory parameters";

# Ignored parameter
sub process ($first, $, $third) {
    return "$first:$third";
}
my $process = process('a', 'b', 'c');
print "not " if $process ne 'a:c';
say "ok # Ignored parameter <$process>";

# Optional parameters with default values
sub greet ($name = "World") {
    return "Hello, $name";
}
print "not " if greet() ne 'Hello, World';
say "ok # Optional parameter default";

# Default value based on previous parameter
sub nickname ($first, $last, $nick = $first) {
    return "$first $last ($nick)";
}
print "not " if nickname('John', 'Doe') ne 'John Doe (John)';
say "ok # Default from previous parameter";

# //= operator default
sub default_undef ($value //= 'default') {
    return $value;
}
print "not " if default_undef(undef) ne 'default';
say "ok # //= default operator";

# ||= operator default
sub default_false ($value ||= 100) {
    return $value;
}
print "not " if default_false(0) != 100;
say "ok # ||= default operator";

# Slurpy array
sub sum ($initial, @rest) {
    return $initial + sum_array(@rest);
}
sub sum_array (@numbers) {
    my $total = 0;
    $total += $_ for @numbers;
    return $total;
}
print "not " if sum(1, 2, 3, 4) != 10;
say "ok # Slurpy array";

# Slurpy hash
sub format_user ($prefix, %data) {
    return "$prefix: $data{name} is $data{age}";
}
print "not " if format_user('User', name => 'Alice', age => 30) ne 'User: Alice is 30';
say "ok # Slurpy hash";

# Nameless slurpy array
sub first_only ($first, @) {
    return $first;
}
print "not " if first_only(1, 2, 3) != 1;
say "ok # Nameless slurpy array";

# Nameless slurpy hash
sub prefix_only ($prefix, %) {
    return $prefix;
}
print "not " if prefix_only('test', a => 1, b => 2) ne 'test';
say "ok # Nameless slurpy hash";

# Multiple optional parameters
sub multi_optional ($a = 1, $b = 2, $c = 3) {
    return $a + $b + $c;
}
print "not " if multi_optional(10) != 15;  # 10 + 2 + 3
say "ok # Multiple optional parameters";

## # Attribute with signature
## sub marked :lvalue ($x) {
##     my $val = $x * 2;
##     return $val;
## }
## print "not " if marked(5) != 10;
## say "ok # Attribute with signature";

