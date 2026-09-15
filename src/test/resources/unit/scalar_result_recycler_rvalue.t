use strict;
use warnings;
use Test::More tests => 3;

our $global = 10;
sub returned_global { return $global }

my $global_result = returned_global();
$global_result = 20;
is($global, 10, 'scalar return remains a copied global rvalue');

my $captured = 30;
my $closure = sub { return $captured };
my $captured_result = $closure->();
$captured_result = 40;
is($captured, 30, 'scalar return remains a copied captured rvalue');

my @list_result = $closure->();
$list_result[0] = 50;
is($captured, 30, 'list-context return remains a copied captured rvalue');
