use strict;
use warnings;
use threads;
use threads::shared;

print "1..7\n";

my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $scalar = 7;
share($scalar);
check($scalar == 7 && is_shared($scalar),
    'share preserves and marks an ordinary scalar');

$scalar = 9;
share($scalar);
check($scalar == 9 && is_shared($scalar),
    'sharing an already shared scalar preserves its value');

my @array = (1, 2);
share(@array);
check(@array == 0 && is_shared(@array),
    'share clears and marks an ordinary array');
push @array, 3;
share(@array);
check(@array == 0, 'resharing an array clears it again');

my %hash = (a => 1);
share(%hash);
check(keys(%hash) == 0 && is_shared(%hash),
    'share clears and marks an ordinary hash');

my $object = bless({ value => 1 }, 'SharedDestructiveObject');
share($object);
check(ref($object) eq 'SharedDestructiveObject'
        && keys(%$object) == 0 && is_shared($object),
    'share retains blessing while clearing aggregate contents');

my $source = { value => 1, nested => [2, 3] };
my $clone = shared_clone($source);
check($clone->{value} == 1 && @{$clone->{nested}} == 2
        && is_shared($clone) && is_shared($clone->{nested})
        && !is_shared($source),
    'shared_clone preserves a recursive copy and leaves its source ordinary');
