use strict;
use warnings;
use threads;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my ($scalar, @array, %hash);

check(\lock($scalar) == \$scalar,
    'scalar lock is compatible before threads::shared loads');
check(lock(@array) == \@array,
    'array lock is compatible before threads::shared loads');
check(lock(%hash) == \%hash,
    'hash lock is compatible before threads::shared loads');
