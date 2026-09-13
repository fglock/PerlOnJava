use strict;
use warnings;
use Scalar::Util qw(refaddr);
use Test::More;

my @word = (0x1234_5678, 0x0f0f_0f0f, 0x55aa_55aa, 7);
my @out = (0);
my $slot = \$out[0];
my $left = $word[0];
my $cell = $word[1];
my $right = $word[2];
$out[0] = ((($cell << 1) | ($left >> 3)) ^ ($right & $word[3])) & 0xffff_ffff;
is($out[0], 509_517_533, 'lexical scalar and direct-array leaves keep unsigned word semantics');
is(refaddr($slot), refaddr(\$out[0]), 'direct word store preserves existing array-element identity');

{
    package NativeWordScalarTie;
    sub TIESCALAR { bless { value => $_[1], events => $_[2], name => $_[3] }, $_[0] }
    sub FETCH { push @{$_[0]{events}}, "FETCH:$_[0]{name}"; $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

my @events;
tie my $tied_left, 'NativeWordScalarTie', $word[0], \@events, 'left';
my @fallback = (0);
$fallback[0] = (($tied_left << 1) | $word[1]) & 0xffff_ffff;
is($fallback[0], 795_848_703, 'tied scalar leaf falls back to ordinary word evaluation');
my @seen_in_order;
my %seen;
push @seen_in_order, $_ for grep { !$seen{$_}++ } @events;
is_deeply(\@seen_in_order, ['FETCH:left'], 'tied scalar retains ordinary FETCH ordering');

done_testing;
