use strict;
use warnings;
use Test::More;
use File::Spec;

our ($begin_closure, $begin_caller_package);
BEGIN {
    $begin_closure = sub { $begin_caller_package = caller(0) };
}

{
    package BeginClosureChild;
    BEGIN { $begin_closure->() }
}

is($begin_caller_package, 'BeginClosureChild',
    'caller(0) in a BEGIN-invoked closure uses the active package');

my $node_module_available = 0;
for my $inc (@INC) {
    next if ref $inc;
    if (-f File::Spec->catfile($inc, qw(ExtUtils ParseXS Node.pm))) {
        $node_module_available = 1;
        last;
    }
}
SKIP: {
    skip 'ExtUtils::ParseXS::Node is not installed', 2 unless $node_module_available;
    my $loaded = eval { require ExtUtils::ParseXS::Node; 1 };
    ok($loaded, 'large ExtUtils::ParseXS::Node compiles its BEGIN closures');
    is_deeply(\@ExtUtils::ParseXS::Node::ISA, [],
        'BEGIN closure leaves the base Node package hierarchy unchanged');
}

done_testing();
