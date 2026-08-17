use strict;
use warnings;
use Test::More tests => 5;

my $comment = qr/##/x;
is(length "$comment", 9, 'trailing /x comment has canonical qr length');
is("$comment", "(?^x:##\n)", 'trailing /x comment source survives stringification');
ok('anything' =~ $comment, 'preserved /x comment remains semantically empty');

my $class = qr/[#]/x;
is("$class", '(?^x:[#])', 'hash in a character class does not gain a newline');

my $inline = qr/(?# inline)/x;
is("$inline", '(?^x:(?# inline))', 'inline regex comment does not gain a newline');
