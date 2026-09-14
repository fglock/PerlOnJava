use Test::More tests => 1;

eval '/$0{}/';

like $@,
    qr/^syntax error at /,
    'empty hash interpolation in a pattern is rejected at compile time';
