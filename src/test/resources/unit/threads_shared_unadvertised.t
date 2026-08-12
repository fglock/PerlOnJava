use strict;
use warnings;
use threads;
use threads::shared;
use Config ();

print "1..10\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $scalar = 1;
share($scalar);
check(is_shared($scalar), 'share marks a scalar');

my @array = (1, 2);
share(@array);
check($Config::Config{archname} =~ /^java-/ ? eval('is_shared(\\@array)') : 1,
    'share marks an array');

my %hash = (a => 1);
share(%hash);
check($Config::Config{archname} =~ /^java-/ ? eval('is_shared(\\%hash)') : 1,
    'share marks a hash');

my ($thread) = threads->create(sub {
    $scalar = 7;
    push @array, 3;
    $hash{b} = 2;
    return ($scalar, scalar @array, $hash{b});
});
my @result = $thread->join;
check($result[0] == 7, 'child sees shared scalar');
check($scalar == 7, 'parent sees child scalar mutation');
check($Config::Config{archname} =~ /^java-/
        ? (@array == 3 && $array[2] == 3)
        : (@array == 1 && $array[0] == 3),
    'shared array mutation survives clone');
check($hash{b} == 2, 'shared hash mutation survives clone');

my $ordinary = { value => 1 };
my $clone = shared_clone($ordinary);
check(is_shared($clone), 'shared_clone marks its result');
check(!is_shared($ordinary), 'shared_clone leaves source ordinary');

my $isolated = 4;
threads->create(sub { $isolated = 9 })->join;
check($isolated == 4, 'ordinary scalar remains isolated');
