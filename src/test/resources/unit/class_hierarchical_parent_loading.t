use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

use lib 'perl5_t/t/lib/class';
use A::B;

ok(A::B->new->isa('A'), 'loading a nested class loads its enclosing :isa class');

done_testing;
