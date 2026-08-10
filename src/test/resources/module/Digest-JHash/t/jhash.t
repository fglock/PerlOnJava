use Test::More tests => 6;
use Digest::JHash qw(jhash);

is(Digest::JHash::jhash("hello world"), 447289830, 'direct call');
is(jhash("goodbye cruel world"), 969307542, 'exported call');
is(jhash(undef), 0, 'undef hashes as the empty string');
is(jhash(''), 0, 'empty string');
is(jhash('a' x 12), 234809978, 'full 12-byte block');
is(jhash("\x00\xff\x80"), 910699166, 'binary octets');
