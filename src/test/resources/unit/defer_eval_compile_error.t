use strict;
use warnings;
use feature 'defer';
use Test::More;

my $ok = eval 'defer { use strict; foo }';
my $error = $@;
ok !$ok, 'strict subs error in defer is caught by eval';
like $error,
    qr/^Bareword "foo" not allowed while "strict subs" in use at \(eval \d+\) line 1\./,
    'deferred closure compilation error has its eval source location without stack frames';

done_testing();
