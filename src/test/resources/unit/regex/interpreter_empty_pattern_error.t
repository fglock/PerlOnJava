use strict;
use warnings;
use Test::More tests => 3;

local $_ = 'ab';
my $pattern = qr/(?{ s!!x! })/;
my $ok = eval {
    /$pattern/;
    /a/;
    /$pattern/;
    /b/;
    /$pattern/;
    //;
    1;
};

ok(!$ok, 'empty-pattern recursion dies');
like($@, qr/^Infinite recursion via empty pattern/,
     'empty-pattern recursion keeps the Perl diagnostic prefix');
unlike($@, qr/Interpreter error|\(pc=\d+\)/,
       'backend implementation context does not decorate the Perl error');
