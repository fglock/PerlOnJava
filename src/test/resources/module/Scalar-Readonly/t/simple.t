use strict;
use warnings;
use Test::More;
use Scalar::Readonly ':all';

my $foo;

eval { $foo = "foo"; };
ok(!$@, "assigning to read/write scalar");

ok(!readonly($foo), "readonly() should return false");

readonly_on($foo);

eval { $foo = "bar"; };
ok($@, "shouldn't be able to change variable");

ok(readonly($foo), "readonly() should return true");

readonly_off($foo);

ok(!readonly($foo), "readonly() should return false again");
eval { $foo = 'xyzzy'; };
ok(!$@, "assigning to scalar should succeed again");

done_testing();
