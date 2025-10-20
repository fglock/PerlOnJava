use strict;
use Test::More;
use feature 'say';
use feature 'postderef';

# Test postderef feature
{
    my $array_ref = [ 11, 22, 33 ];
    my @arr       = $array_ref->@*;
    ok(!("@arr" != "11 22 33"), 'Array postderef');

    my @slice = $array_ref->@[ 2, 1 ];
    ok(!("@slice" != "33 22"), 'Array postderef slice');
}

{
    my $hash_ref = { aa => 11, bb => 22, cc => 33 };
    my @arr       = $hash_ref->%*;
    ok(!(scalar(@arr) != 6 || !grep {$_ eq "aa"} @arr), 'Hash postderef <@arr>');

    my @slice = $hash_ref->@{ "cc", "bb" };
    ok(!("@slice" != "33 22"), 'Hash postderef slice');
}

my $x          = 123;
my $scalar_ref = \$x;
ok(!($scalar_ref->$* ne 123), 'Scalar postderef');

my $sub_ref = sub { return "@_, World!" };
@_ = ("Hello");
ok(!($sub_ref->&* ne "Hello, World!"), 'Subroutine postderef');

done_testing();
