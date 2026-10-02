use strict;
use warnings;
use Test::More;

my $data = "";
open my $fh, '<', \$data or die "open scalar handle: $!";
my $err = 'EIQ - Quoted field not terminated';

my $keep_error = !eof $fh || $err !~ /^EOF/;
ok($keep_error, 'low-precedence logical operator stays outside unary eof');

my $tell_data = "x\n";
open my $tell_fh, '<', \$tell_data or die "open tell handle: $!";
scalar readline $tell_fh;
my $tell_rhs = 0;
my $tell_condition = !tell $tell_fh || ++$tell_rhs;
ok($tell_condition && $tell_rhs == 1,
    'low-precedence logical operator stays outside unary tell');

my $readline_data = "x\n";
open my $readline_fh, '<', \$readline_data or die "open readline handle: $!";
my $readline_rhs = 0;
my $readline_condition = !readline $readline_fh || ++$readline_rhs;
ok($readline_condition && $readline_rhs == 1,
    'low-precedence logical operator stays outside unary readline');

done_testing;
