use strict;
use warnings;
use English qw(@ARG);
use Test::More tests => 2;

sub arguments_via_english { return [@ARG] }

my $got = arguments_via_english(qw(first second));
is scalar(@$got), 2, 'English @ARG contains all subroutine arguments';
is_deeply $got, [qw(first second)], 'English @ARG preserves argument order';
