use strict;
use warnings;
use feature 'switch';
use Test::More;

sub returns_true { 'truthy' }

{
    my $matched = 0;
    given ('topic') {
        when (returns_true()) { $matched = 1 }
    }
    is $matched, 1, 'direct subroutine when condition is boolean';
}

{
    my $matched = 0;
    given ('topic') {
        when (main->returns_true()) { $matched = 1 }
    }
    is $matched, 1, 'class method when condition is boolean';
}

done_testing;
