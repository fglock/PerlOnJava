use strict;
use warnings;
use Test::More;

our $result = '';

eval q{
    $main::result .= __PACKAGE__;
    goto TARGET;
    package GotoLabelPackage;
    TARGET: $main::result .= __PACKAGE__;
    package main;
};

is($@, '', 'goto across package declaration succeeds');
is($result, 'mainGotoLabelPackage',
    'goto resumes with the runtime package of its destination label');

done_testing();
