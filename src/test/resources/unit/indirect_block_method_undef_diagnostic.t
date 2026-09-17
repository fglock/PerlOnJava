use strict;
use warnings;
use Test::More;

no warnings 'unopened';
eval 'E{0;readline @0}';
like($@,
   qr{\ACan't call method "E" without a package or object reference at \(eval \d+\) line 1\.\n\z},
   'an indirect block method call distinguishes an absent object from undef');

done_testing;
