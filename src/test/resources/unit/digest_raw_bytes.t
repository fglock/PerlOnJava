use strict;
use warnings;
use Test::More tests => 6;

use Digest::MD5;
use Digest::SHA;
use bytes ();

my $md5 = Digest::MD5->new->add('PerlOnJava')->digest;
my $sha = Digest::SHA->new(1)->add('PerlOnJava')->digest;

is(length($md5), 16, 'MD5 digest has 16 characters');
is(bytes::length($md5), 16, 'MD5 digest is a 16-byte string');
is(unpack('H*', $md5), 'ea1e008d38b68b395c5540a9a251484d', 'MD5 digest bytes are correct');

is(length($sha), 20, 'SHA-1 digest has 20 characters');
is(bytes::length($sha), 20, 'SHA-1 digest is a 20-byte string');
is(unpack('H*', $sha), '2ff886784802a050c7b873a6c4dd0521854f8690', 'SHA-1 digest bytes are correct');
