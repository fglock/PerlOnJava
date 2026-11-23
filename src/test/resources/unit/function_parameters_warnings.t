use strict;
use warnings;
use Test::More;

BEGIN {
    my $loaded = eval { require Function::Parameters; Function::Parameters->import(); 1 };
    if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
        plan skip_all => 'Function::Parameters is not installed';
    }
    die $@ unless $loaded;
}

my $suppressed = eval q{
    no warnings 'Function::Parameters';
    1;
};
ok($suppressed, 'Function::Parameters warning category is registered');
is($@, '', 'warning suppression does not report an unknown category');

done_testing;
