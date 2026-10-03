use strict;
use utf8;
use open qw( :utf8 :std );
use Test::More tests => 1;

package ｍａｉｎ;
my @c;
sub ｆｏｏ { @c = caller(0) }
{
    no strict 'refs';
    no warnings 'utf8';
    () = *{"ｆｏｏ"};
}
my $fooref = delete $ｍａｉｎ::{ｆｏｏ};
$fooref->();
::is($c[3], "ｍａｉｎ::__ANON__", 'deleted named CV is reported as anonymous');
