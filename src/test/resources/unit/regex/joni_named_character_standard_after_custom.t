use strict;
use warnings;
use Test::More tests => 2;

{
    use lib 'src/test/resources/unit/lib';
    use Phase36Cname;

    ok('foo' =~ /^\N{foo}$/, 'custom charname is active in its lexical scope');
}

{
    use charnames ':full';

    ok("\0" =~ /^\N{NULL}$/,
        'standard regex charname is restored after custom scope');
}
