use strict;
use warnings;
use Test::More tests => 1;

# The protocol is based on Tie::MultiKeyInsertOrderHash 0.1. Its repeated
# keys and FETCH cache make values %hash exercise the real tied iteration path.
{
    package CachedIterationTie;

    sub TIEHASH { bless [[], {}, 0, {}, undef], shift }
    sub STORE {
        push @{$_[0]->[0]}, $_[1];
        $_[0]->[2] = -1;
        push @{$_[0]->[1]{$_[1]}}, $_[2];
    }
    sub FETCH {
        if ($_[0]->[4] && $_[0]->[4][0] eq $_[1]) {
            my $value = $_[0]->[4][1];
            $_[0]->[4] = undef;
            return $value;
        }
        return $_[0]->[1]{$_[1]};
    }
    sub FIRSTKEY {
        $_[0]->[3] = {};
        $_[0]->[2] = 0;
        return $_[0]->[4] = undef unless defined $_[0]->[0][0];
        my $key = $_[0]->[0][0];
        $_[0]->[3]{$key} = 1 unless $_[0]->[3]{$key};
        $_[0]->[4] = [$key, $_[0]->[1]{$key}[0]];
        return $key;
    }
    sub NEXTKEY {
        my $i = $_[0]->[2];
        return $_[0]->[4] = undef unless defined $_[0]->[0][$i];
        if ($_[0]->[0][$i] eq $_[1]) {
            $i = ++$_[0]->[2];
            return $_[0]->[4] = undef unless defined $_[0]->[0][$i];
        }
        my $key = $_[0]->[0][$i];
        $_[0]->[3]{$key} = 0 unless $_[0]->[3]{$key};
        $_[0]->[3]{$key}++;
        $_[0]->[4] = [$key, $_[0]->[1]{$key}[$_[0]->[3]{$key} - 1]];
        return $key;
    }
    sub EXISTS { exists $_[0]->[1]{$_[1]} }
}

my %hash;
tie %hash, 'CachedIterationTie';
$hash{A} = '1';
$hash{B} = '2';
$hash{A} = '3';
$hash{B} = '4';
$hash{C} = '5';

my @keys = keys %hash;
my @pairs;
while (my @pair = each %hash) { push @pairs, \@pair }

is_deeply(
    [values %hash],
    [[1, 3], [2, 4], [1, 3], [2, 4], [5]],
    'values preserves reference results after key and each traversals',
);
