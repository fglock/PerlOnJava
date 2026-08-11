use strict;
use warnings;
use Test::More tests => 5;

my $destructive = 'SomeKeyword';
my @destructive_pos;
$destructive =~ s{[A-Z]}{push @destructive_pos, pos($destructive); lc $&}ge;

is $destructive, 'somekeyword', 'destructive evaluated substitution replaces matches';
is_deeply \@destructive_pos, [0, 4], 'destructive replacement code sees each match start in pos';
ok !defined(pos($destructive)), 'destructive substitution invalidates pos after mutation';

my $source = 'SomeKeyword';
pos($source) = 3;
my @nondestructive_pos;
my $copy = $source =~ s{[A-Z]}{push @nondestructive_pos, pos($source); lc $&}ger;

is_deeply \@nondestructive_pos, [3, 3], 'non-destructive replacement preserves original pos during evaluation';
is pos($source), 3, 'non-destructive substitution preserves source pos';
