use strict;
use warnings;
use Test::More tests => 3;

{
    use lib 'src/test/resources/unit/lib';
    use RegexImplementationCname;

    is("\N{foo}", 'foo', 'first nested lexical expansion');
    is("\N{bar}", 'bar', 'second nested lexical expansion');
    ok('foo' =~ /^\N{foo}$/, 'regex expansion after strings');
}
