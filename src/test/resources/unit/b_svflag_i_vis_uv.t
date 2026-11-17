use strict;
use warnings;

use B qw(SVf_IVisUV SVf_IOK svref_2object);
use Test::More tests => 5;

is(SVf_IVisUV(), 0x80000000, 'SVf_IVisUV can be imported with its Perl value');
is(B::SVf_IVisUV(), 0x80000000, 'SVf_IVisUV is available as a package function');

my $uv = 18446744073709551615;
my $uv_flags = svref_2object(\$uv)->FLAGS;
ok(($uv_flags & SVf_IVisUV()) && ($uv_flags & SVf_IOK()),
    'B flags identify an unsigned integer scalar');

my $iv = 9223372036854775807;
my $iv_flags = svref_2object(\$iv)->FLAGS;
ok(!($iv_flags & SVf_IVisUV()),
    'B flags leave the largest signed integer marked as signed');

my $uv_string = '18446744073709551614';
my $parsed_uv = $uv_string + 0;
my $parsed_uv_flags = svref_2object(\$parsed_uv)->FLAGS;
ok(($parsed_uv_flags & SVf_IVisUV()) && ($parsed_uv_flags & SVf_IOK()),
    'B flags retain UV status when an unsigned integer string is numified');
