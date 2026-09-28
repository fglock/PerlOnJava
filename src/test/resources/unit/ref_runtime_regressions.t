use strict;
use warnings;
use Test::More;
use Scalar::Util qw(reftype);
use re;

my $regexp = qr/x/;
my $regexp_value = $$regexp;
my $regexp_ref = \$regexp_value;
ok(re::is_regexp($regexp_ref), 'reference to a dereferenced regexp keeps regexp identity');
is("$regexp_ref", "$regexp", 'reference to a dereferenced regexp stringifies as regexp');

my $hash_object = bless {}, 'RefRuntimeRegression::Hash';
is(builtin::reftype($hash_object), 'HASH', 'builtin reftype ignores ordinary blessing');

my $value;
my $substr_ref = \substr($value, 0, 0);
is(ref($substr_ref), 'LVALUE', 'substr reference reports LVALUE');
is(builtin::reftype($substr_ref), 'LVALUE', 'builtin reftype reports substr LVALUE');

my @refs = \(1..2, 3);
is(scalar @refs, 3, 'refgen distributes over a range in a surrounding list');
is(scalar(grep { ref($_) eq 'SCALAR' } @refs), 3, 'range refgen produces scalar references');

done_testing;
