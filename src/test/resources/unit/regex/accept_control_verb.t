use strict;
use warnings;
use Test::More;

my $after = 0;
ok('ABDE' =~ /(A(A|B(*ACCEPT)|C)+D)(E)(?{ ++$after })/,
    'accept succeeds before the outer suffix');
is($&, 'AB', 'accept fixes the overall endpoint');
is("$1-$2", 'AB-B', 'accept closes active captures');
is($after, 0, 'accept skips later callbacks');

ok('x' =~ /(*ACCEPT)never/, 'accept may produce an empty match');
is($&, '', 'empty accept has the current endpoint');

my $sub = qr/(?(DEFINE)(?<a>(?:[ab]|[cd](*ACCEPT)|[ef])g))(?&a)(?&a)/;
ok('cc' =~ $sub, 'accept returns from a subroutine call');
is($&, 'cc', 'outer program continues after accepted subroutine calls');
ok('agag' =~ $sub, 'ordinary subroutine paths retain their suffixes');

ok('ab' =~ /(?=(a(*ACCEPT)z))ab/,
    'accept completes a positive assertion boundary');
is($1, 'a', 'accept closes captures inside a positive assertion');

ok('ab' =~ /(??{ 'a(*ACCEPT)z' })b/,
    'accept completes a dynamic nested program');
is($&, 'ab', 'outer suffix continues after dynamic accept');

done_testing();
