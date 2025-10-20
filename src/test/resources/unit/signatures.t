use strict;
use warnings;
use Test::More;

use v5.36;

###################
# Perl 5 Signatures Tests

# Empty signature
sub no_args () { 
    return 42;
}
ok(!(no_args() != 42), 'Empty signature');

# Basic mandatory parameters
sub add ($left, $right) {
    return $left + $right;
}
ok(!(add(2, 3) != 5), 'Basic mandatory parameters');

# Ignored parameter
sub process ($first, $, $third) {
    return "$first:$third";
}
my $process = process('a', 'b', 'c');
ok(!($process ne 'a:c'), 'Ignored parameter <$process>');

# Optional parameters with default values
sub greet ($name = "World") {
    return "Hello, $name";
}
ok(!(greet() ne 'Hello, World'), 'Optional parameter default');

# Default value based on previous parameter
sub nickname ($first, $last, $nick = $first) {
    return "$first $last ($nick)";
}
ok(!(nickname('John', 'Doe') ne 'John Doe (John)'), 'Default from previous parameter');

# //= operator default
sub default_undef ($value //= 'default') {
    return $value;
}
ok(!(default_undef(undef) ne 'default'), '//= default operator');

# ||= operator default
sub default_false ($value ||= 100) {
    return $value;
}
ok(!(default_false(0) != 100), '||= default operator');

# Slurpy array
sub sum ($initial, @rest) {
    return $initial + sum_array(@rest);
}
sub sum_array (@numbers) {
    my $total = 0;
    $total += $_ for @numbers;
    return $total;
}
ok(!(sum(1, 2, 3, 4) != 10), 'Slurpy array');

# Slurpy hash
sub format_user ($prefix, %data) {
    return "$prefix: $data{name} is $data{age}";
}
ok(!(format_user('User', name => 'Alice', age => 30) ne 'User: Alice is 30'), 'Slurpy hash');

# Nameless slurpy array
sub first_only ($first, @) {
    return $first;
}
ok(!(first_only(1, 2, 3) != 1), 'Nameless slurpy array');

# Nameless slurpy hash
sub prefix_only ($prefix, %) {
    return $prefix;
}
ok(!(prefix_only('test', a => 1, b => 2) ne 'test'), 'Nameless slurpy hash');

# Multiple optional parameters
sub multi_optional ($a = 1, $b = 2, $c = 3) {
    return $a + $b + $c;
}
ok(multi_optional(10) == 15, 'Multiple optional parameters');  # 10 + 2 + 3

# Attribute with signature
sub marked :lvalue ($x) {
    my $val = $x * 2;
    return $val;
}
ok(!(marked(5) != 10), 'Attribute with signature');

done_testing();
