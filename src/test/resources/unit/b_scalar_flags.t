use strict;
use warnings;
use Test::More;
use B qw(svref_2object SVf_IOK SVf_NOK SVf_POK);

my $iv = 7;
my $iv_flags = svref_2object(\$iv)->FLAGS;
is($iv_flags & (SVf_IOK | SVf_NOK | SVf_POK), SVf_IOK,
    'integer scalar reports IOK');

my $nv = sin(1);
my $nv_flags = svref_2object(\$nv)->FLAGS;
is($nv_flags & (SVf_IOK | SVf_NOK | SVf_POK), SVf_NOK,
    'non-integral numeric scalar reports NOK');

my $pv = '7';
my $pv_flags = svref_2object(\$pv)->FLAGS;
is($pv_flags & (SVf_IOK | SVf_NOK | SVf_POK), SVf_POK,
    'numeric-looking string reports POK before numification');

my $numified = 0 + $pv;
my $numified_flags = svref_2object(\$pv)->FLAGS;
is($numified_flags & (SVf_IOK | SVf_NOK | SVf_POK), SVf_IOK | SVf_POK,
    'numified numeric-looking string retains POK and adds IOK');

done_testing();
