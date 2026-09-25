use strict;
use warnings;
use Test::More;

open my $fh, '>', \my $output or die "open: $!";
my $binmode = \&{'CORE::binmode'};
is($binmode->($fh, ':raw'), 1,
    'dynamic CORE::binmode reference invokes the runtime operator');
close $fh;

done_testing;
