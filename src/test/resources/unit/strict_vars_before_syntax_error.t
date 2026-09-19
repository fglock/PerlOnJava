use strict;
use warnings;
use Test::More;

my $ok = eval q{
    use warnings FATAL => 'all';
    use strict;
    $foo;
    myfunc 1, 2, 3;
};

ok(!$ok, 'strict and syntax diagnostics reject the source');
like($@, qr/Global symbol "\$foo" requires explicit package name/, 'reports strict-vars error first');
like($@, qr/Number found where operator expected/, 'retains later parser warning');
like($@, qr/syntax error/, 'retains later syntax error');

done_testing;
