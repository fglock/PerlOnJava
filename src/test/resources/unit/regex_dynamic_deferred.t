use strict;
use warnings;
use Test::More tests => 1;

our $nested;
$nested = qr{ \( (?: [^()]+ | (??{ $nested }) )* \) }x;
ok(ref($nested) eq 'Regexp', 'dynamic recursive regex can be constructed');
