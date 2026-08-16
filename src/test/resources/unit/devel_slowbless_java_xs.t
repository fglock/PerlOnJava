use strict;
use warnings;
use Test::More;

BEGIN {
    plan skip_all => 'PerlOnJava Java XS bridge test' unless $^X =~ /jperl/;
}

use Devel::SlowBless;

my $before = Devel::SlowBless::sub_gen();
eval 'sub SlowBlessGeneration::installed { 42 }';
is($@, '', 'named subroutine compiles through eval');
cmp_ok(Devel::SlowBless::sub_gen(), '>', $before,
    'sub_gen advances when a named subroutine is installed');

is(Devel::SlowBless::amg_gen(), 0,
    'amg_gen is zero on modern Perl-compatible runtimes');

done_testing;
