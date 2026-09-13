use strict;
use warnings;
use Scalar::Util qw(refaddr);
use Test::More;

my @word = (0x1234_5678, 0x0f0f_0f0f, 0x55aa_55aa, 7);
my @out = (0);
my $slot = \$out[0];
$out[0] = ((($word[0] << 1) | ($word[1] >> 3)) ^ ($word[2] & $word[3])) & 0xffff_ffff;
is($out[0], 636_087_795, 'ordinary direct array tree keeps unsigned word semantics');
is(refaddr($slot), refaddr(\$out[0]), 'direct word store preserves existing array-element identity');

{
    package NativeWordArrayTie;

    sub TIEARRAY { bless { values => $_[1], events => $_[2] }, $_[0] }
    sub FETCHSIZE { scalar @{$_[0]{values}} }
    sub FETCH {
        push @{$_[0]{events}}, "FETCH:$_[1]";
        return $_[0]{values}[$_[1]];
    }
    sub STORE { $_[0]{values}[$_[1]] = $_[2] }
}

my @events;
tie my @tied, 'NativeWordArrayTie', [@word], \@events;
my @fallback = (0);
$fallback[0] = ((($tied[0] << 1) | ($tied[1] >> 3)) ^ ($tied[2] & $tied[3])) & 0xffff_ffff;
is($fallback[0], 636_087_795, 'tied source falls back to ordinary word evaluation');
my @seen_in_order;
my %seen;
push @seen_in_order, $_ for grep { !$seen{$_}++ } @events;
is_deeply(\@seen_in_order, [qw(FETCH:0 FETCH:1 FETCH:2 FETCH:3)],
    'tied leaves retain ordinary left-to-right FETCH ordering');

done_testing;
