use strict;
use warnings;
use feature 'postderef';
no warnings 'experimental::postderef';
use Test::More tests => 3;

sub PVBM () { 'foo' }

my $postfix = \'PVBM'->&*;
is(ref($postfix), 'CODE', 'reference to postfix code dereference is a coderef');
is("$postfix", "" . \&PVBM, 'postfix and prefix coderef references agree');

no strict 'refs';
is('PVBM'->&*, 'foo', 'ordinary postfix code dereference still invokes the subroutine');
