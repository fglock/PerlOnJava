use strict;
use warnings;
use integer;
use Test::More;

my @word = (0x1234_5678, 0x0f0f_0f0f, 0x55aa_55aa, 7);
my $got = ((($word[0] << 1) | ($word[1] >> 3)) ^ ($word[2] & $word[3]));
my $expected = ((($word[0] << 1) | ($word[1] >> 3)) ^ ($word[2] & $word[3]));
is($got, $expected, 'nested integer bitwise tree preserves an ordinary array result');

{
    package IntegerBitwiseTreeTie;
    sub TIESCALAR { bless { value => $_[1], log => $_[2] }, $_[0] }
    sub FETCH { push @{$_[0]{log}}, 'fetch'; return $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

my @events;
tie my $tied, 'IntegerBitwiseTreeTie', 3, \@events;
my $fallback = (1 & $tied) | 4;
is($fallback, 5, 'tied leaf falls back to ordinary integer bitwise evaluation');
is_deeply(\@events, ['fetch'], 'tied leaf FETCH remains observable exactly once');

{
    package IntegerBitwiseTreeOverload;
    use overload '&' => sub { ${$_[0]{log}} .= 'and'; return 2 }, fallback => 1;
    sub new { bless { log => $_[1] }, $_[0] }
}

my $log = '';
my $object = IntegerBitwiseTreeOverload->new(\$log);
my $overloaded = ($object & 3) | 4;
is($overloaded, 6, 'overloaded intermediate falls back to the ordinary tree');
is($log, 'and', 'left overload runs before the enclosing bitwise operation');

done_testing;
