use strict;
use warnings;
use Test::More;

{
    no strict 'refs';
    my $array_ref = [];
    my $ok = eval { *$array_ref = 1; 1 };
    ok(!$ok, 'an array reference cannot be assigned through a glob dereference');
    like($@, qr/^Not a GLOB reference at/, 'wrong reference type reports a glob-reference error');
}

done_testing;
