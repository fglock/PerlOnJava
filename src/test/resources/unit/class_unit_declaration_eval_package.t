use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';

my $package_context;
eval q{
    class UnitDeclarationEvalPackage;
    $package_context = __PACKAGE__ . '/' . eval('__PACKAGE__');
    package main;
    1;
} or die $@;

print "1..1\n";
if ($package_context eq 'UnitDeclarationEvalPackage/UnitDeclarationEvalPackage') {
    print "ok 1 - eval inside a unit class declaration uses the current class package\n";
} else {
    print "not ok 1 - eval inside a unit class declaration uses the current class package\n";
    print "# got: $package_context\n";
}
