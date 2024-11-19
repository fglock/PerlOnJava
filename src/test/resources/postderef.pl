use strict;
use feature 'say';
use feature 'postderef';

# Test postderef feature
{
    my $array_ref = [ 11, 22, 33 ];
    my @arr       = $array_ref->@*;
    print "not " if "@arr" != "11 22 33";
    say "ok # Array postderef";

    ## my @slice = $array_ref->@[ 2, 1 ];
    ## print "not " if "@slice" != "33 22";
    ## say "ok # Array postderef slice";
}

{
    my $hash_ref = { aa => 11, bb => 22, cc => 33 };
    my @arr       = $hash_ref->%*;
    print "not " if scalar(@arr) != 6 || !grep {$_ eq "aa"} @arr;
    say "ok # Hash postderef <@arr>";

    ## my @slice = $hash_ref->@{ "cc", "bb" };
    ## print "not " if "@slice" != "33 22";
    ## say "ok # Hash postderef slice";
}

my $x          = 123;
my $scalar_ref = \$x;
print "not " if $scalar_ref->$* ne 123;
say "ok # Scalar postderef";

my $sub_ref = sub { return "@_, World!" };
@_ = ("Hello");
print "not " if $sub_ref->&* ne "Hello, World!";
say "ok # Subroutine postderef";
