use strict;
use warnings;
use threads::shared;

print "1..4\n";
my $test = 0;
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

my @array :shared;
my %hash :shared;
my $private = { value => 1 };

ok(!is_shared(@array) && !is_shared(%hash),
    ':shared is inactive when threads has not been loaded');

my $stored = eval {
    push @array, $private;
    $hash{private} = $private;
    1;
};
ok($stored && !$@, 'inactive shared containers accept ordinary references');
ok($array[0] == $private && $hash{private} == $private,
    'inactive containers retain ordinary reference identity');

$array[0]{value} = 2;
ok($private->{value} == 2 && $hash{private}{value} == 2,
    'inactive containers retain ordinary aliasing semantics');
