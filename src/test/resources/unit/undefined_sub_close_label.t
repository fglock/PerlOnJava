use strict;
use warnings;
use Test::More;

package BEEP;
sub boop;

package main;
my $result = eval q{
    BEEP: boop();
    1;
};

ok(!defined($result), 'an undefined direct call dies');
like($@, qr/Undefined subroutine &main::boop called, close to label 'BEEP'/,
    'the undefined-call diagnostic identifies the preceding label');

done_testing;
