use strict;
use warnings;
use Test::More;
use overload ();

my $re = qr/Foo/;

like(overload::StrVal($re), qr/\ARegexp=REGEXP\(0x[0-9a-f]+\)\z/,
    'overload::StrVal on qr returns raw regexp reference');
like(do { no overloading; "$re" }, qr/\ARegexp=REGEXP\(0x[0-9a-f]+\)\z/,
    'no overloading stringification on qr returns raw regexp reference');
is("$re", '(?^:Foo)', 'normal qr stringification is unchanged');

done_testing;
