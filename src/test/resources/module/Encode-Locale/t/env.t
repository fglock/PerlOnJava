use strict;
use warnings;
use Test::More tests => 13;

use Encode::Locale qw(env);

$ENV{foo} = "bar";
is env("foo"), "bar", 'env read';
is env("foo", "baz"), "bar", 'env write retval old value';
is env("foo"), "baz", 'env write worked';
is $ENV{foo}, "baz", 'env affected %ENV';
is env("foo", undef), "baz", 'env write retval old value';
is env("foo"), undef, 'env write worked';
ok !exists $ENV{foo}, 'env write undef deletes from %ENV';

Encode::Locale::reinit("cp1252");
$ENV{"m\xf6ney"} = "\x80uro";
is env("m\xf6ney", "\x{20AC}"), "\x{20AC}uro", 'env write retval encoded';
is env("m\xf6ney"), "\x{20AC}", 'env write worked';
is $ENV{"m\xf6ney"}, "\x80", 'env affected %ENV';
is env("\x{20AC}", 1), undef, 'env write retval old value';
is env("\x{20AC}"), 1, 'env write worked';
is $ENV{"\x80"}, 1, 'env affected %ENV';
