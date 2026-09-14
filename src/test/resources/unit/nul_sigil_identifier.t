use strict;
use warnings;
use Test::More;

my $nul = chr 0;
our ($nul_probe, @nul_probe, %nul_probe);
our %eq;

my $scalar = eval q{$nul_probe = 'scalar'; $} . $nul . q{nul_probe};
is $@, '', 'NUL after scalar sigil compiles';
is $scalar, 'scalar', 'NUL after scalar sigil is ignored';

my $array = eval q{@nul_probe = ('array'); @} . $nul . q{nul_probe};
is $@, '', 'NUL after array sigil compiles';
is $array, 1, 'NUL after array sigil is ignored';

my $hash = eval q{%nul_probe = (key => 'value'); scalar keys %} . $nul . q{nul_probe};
is $@, '', 'NUL after hash sigil compiles';
is $hash, 1, 'NUL after hash sigil is ignored';

my $keyword_hash = eval q{%eq = (key => 'value'); scalar keys %} . $nul . q{eq};
is $@, '', 'NUL before a keyword-named hash compiles';
is $keyword_hash, 1, 'NUL before a keyword-named hash is ignored';

done_testing;
