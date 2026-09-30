use strict;
use warnings;
use Test::More;

plan skip_all => 'construct-entry goto errors require Perl 5.44'
    if $] < 5.044;
plan tests => 6;

my $message = qr/Use of "goto" to jump into a construct is no longer permitted/;

eval { sub { goto e; $#{; do { e: \@_ } } }->(1 .. 7) };
like($@, $message, 'goto into a dereference construct is rejected');

eval { sub { goto f; prototype \&{; do { f: sub ($) {} } } }->() };
like($@, $message, 'goto into a prototype and code reference is rejected');

eval { sub { goto j; defined undef ${; do { j: \(my $foo = "foo") } } }->() };
like($@, $message, 'goto into defined and undef is rejected');

eval { sub { goto k; study ++${; do { k: \(my $foo = "foo") } } }->() };
like($@, $message, 'goto into study and preincrement is rejected');

eval { sub { goto l; ~-!${; do { l: \(my $foo = 0) } }++ }->() };
like($@, $message, 'goto into nested unary operators is rejected');

eval { no warnings 'void'; join ' ', sub { goto v; %{; do { v: +{1 .. 2} } } }->() };
like($@, $message, 'goto into a hash dereference is rejected');
