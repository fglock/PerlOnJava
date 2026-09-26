use strict;
use warnings;
use Test::More tests => 2;

{
    no strict;
    eval "\$x =\xDFfoo";
    like($@,
        qr/Unrecognized character \\xDF; marked by <-- HERE after \$x =<-- HERE near column 5/,
        'ordinary byte eval rejects a non-ASCII identifier byte');
}

eval '1';
is($@, '', 'a successful later eval can clear the byte-source error');
