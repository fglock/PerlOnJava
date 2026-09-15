use strict;
use warnings;
use Test::More;

my ($a, $b, $c) = (10, 20, 30);
my $sum = sub { $a + $b + $c };
is($sum->(), 60, 'captured integer addition returns its scalar result');
$b = 7;
is($sum->(), 47, 'captured integer mutation is observed by the closure');

{
    package DirectLeafOverload;
    use overload '+' => sub { 99 }, fallback => 1;
}
$a = bless {}, 'DirectLeafOverload';
is($sum->(), 129, 'overloaded capture retains ordinary addition semantics');

my $observes_caller = sub { (caller(0))[3] };
is($observes_caller->(), 'main::__ANON__',
    'caller-observing closure retains the ordinary call frame');

my $uses_args = sub { $_[0] };
is($uses_args->(42), 42, 'argument-observing closure retains ordinary argument semantics');

done_testing;
