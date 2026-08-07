use strict;
use warnings;
use Test::More tests => 4;

{
    package Local::TiedHash;
    sub TIEHASH { bless { values => { old => 1 }, clears => 0 }, shift }
    sub CLEAR { $_[0]{values} = {}; ++$_[0]{clears} }
    sub STORE { $_[0]{values}{$_[1]} = $_[2] }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub FIRSTKEY { my $self = shift; keys %{$self->{values}}; each %{$self->{values}} }
    sub NEXTKEY { each %{$_[0]{values}} }
    sub clears { $_[0]{clears} }

    package Local::TiedArray;
    sub TIEARRAY { bless { values => [1, 2], clears => 0 }, shift }
    sub STORESIZE { $#{$_[0]{values}} = $_[1] - 1 }
    sub CLEAR { $_[0]{values} = []; ++$_[0]{clears} }
    sub FETCHSIZE { scalar @{$_[0]{values}} }
    sub clears { $_[0]{clears} }
}

tie my %hash, 'Local::TiedHash';
my $hash_object = tied %hash;
undef %hash;
is_deeply([keys %hash], [], 'undef tied hash dispatches CLEAR');
is($hash_object->clears, 1, 'tied hash CLEAR called once');

tie my @array, 'Local::TiedArray';
my $array_object = tied @array;
undef @array;
is(scalar @array, 0, 'undef tied array dispatches CLEAR');
is($array_object->clears, 1, 'tied array CLEAR called once');
