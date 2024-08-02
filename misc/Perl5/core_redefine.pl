# $ perl -I src5/lib misc/Perl5/core_redefine.pl

use strict;
use Perlito5::Runtime;
use Data::Dumper;

my $ops = $Perlito5::CORE_PROTO;

# print "[ @{[ sort keys %$ops ]} ]\n";

for my $op ( sort keys %$ops ) {
    next unless $op;
    $op =~ s/^CORE:://;
    next if $op eq 'redo';

    local $@;

    my $v = int(rand(10000));
    my $x = 123;
    {;
    eval "use subs '$op'; sub $op { \$x = \$v } "
           . "$op()";
    }

    my $err = $@;
    if ($err =~ /Not enough arguments/) {
        my $arg = "test";
        {;
        eval "use subs '$op'; sub $op { \$x = \$v } "
               . "$op(\$arg)";
        }
        $err = $@;
    }

    # print $err if $err;

    if ($v eq $x) {
        printf "    %-18s => 1,\n", "'$op'";
    }
}

