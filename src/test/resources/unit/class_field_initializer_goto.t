use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class FieldInitializerGoto {
    field $forward = do { goto FORWARD; FORWARD: 1 };
    field $backward = do { my $seen; BACKWARD: goto BACKWARD if !$seen++; 2 };

    method values { return ($forward, $backward) }
}

is_deeply [FieldInitializerGoto->new->values], [1, 2],
    'field initializer do blocks permit local forward and backward goto';

done_testing;
