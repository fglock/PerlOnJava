use strict;
use warnings;
use Test::More;

use feature qw(declared_refs refaliasing state);
no warnings qw(experimental::declared_refs experimental::refaliasing);

my @array_source = ('before');
my $array_result = eval q{
    for my \@x (\@array_source) {
        $x[0] = 'changed';
    }
    $array_source[0];
};
is($@, '', 'my array iterator compiles and runs in eval');
is($array_result, 'changed', 'my array iterator exposes its declared-reference container in the loop body');

my %hash_source = (key => 'before');
my $hash_result = eval q{
    for my \%x (\%hash_source) {
        $x{key} = 'changed';
    }
    $hash_source{key};
};
is($@, '', 'my hash iterator compiles and runs in eval');
is($hash_result, 'changed', 'my hash iterator exposes its declared-reference container in the loop body');

done_testing;
