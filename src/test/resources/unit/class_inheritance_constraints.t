use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $preexisting_isa = eval q{
    BEGIN { push @ClassInheritancePreexisting::ISA, 'Parent'; }
    class ClassInheritancePreexisting {}
    1;
};
ok(!$preexisting_isa, 'a class cannot replace a non-empty existing @ISA');
like($@, qr/Cannot create class ClassInheritancePreexisting as it already has a non-empty \@ISA/,
    'preexisting @ISA has the Perl diagnostic');

my $not_a_class = eval q{
    BEGIN { $INC{'ClassInheritancePlainPackage.pm'} = __FILE__; }
    package ClassInheritancePlainPackage;
    package main;
    class ClassInheritanceChild :isa(ClassInheritancePlainPackage) {}
    1;
};
ok(!$not_a_class, ':isa requires a Perl class rather than an ordinary package');
like($@, qr/Class :isa attribute requires a class but "ClassInheritancePlainPackage" is not one/,
    ':isa diagnostic identifies the ordinary package');

done_testing;
