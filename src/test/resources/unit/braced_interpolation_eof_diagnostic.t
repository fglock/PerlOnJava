use Test::More tests => 1;

eval '"@{"';
like $@, qr/^Missing right curly or square bracket at .* within string\nsyntax error at .* at EOF\n/,
    'unfinished braced interpolation retains quoted-string context';
