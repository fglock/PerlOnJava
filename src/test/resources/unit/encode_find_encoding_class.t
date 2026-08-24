use strict;
use warnings;
use utf8;

use Encode ();
use Scalar::Util qw(refaddr);
use Test::More;

my $encoding = Encode::find_encoding('UTF-8');
isa_ok($encoding, 'Encode::utf8', 'UTF-8 lookup returns the standard encoding subclass');
is($encoding->name, 'utf-8-strict', 'UTF-8 encoding has the standard canonical name');
is($encoding->mime_name, 'UTF-8', 'UTF-8 encoding has the standard MIME name');

my $alias = Encode::find_encoding('utf8');
isa_ok($alias, 'Encode::utf8', 'UTF-8 alias returns the same standard subclass');
is(refaddr(Encode::find_encoding($encoding)), refaddr($encoding),
    'looking up an existing encoding object preserves its identity');
is($alias->decode("\xC3\xA9"), "é", 'UTF-8 subclass inherits decoding behavior');
is($alias->encode("é"), "\xC3\xA9", 'UTF-8 subclass inherits encoding behavior');

done_testing;
