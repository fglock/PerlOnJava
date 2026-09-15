use strict;
use warnings;
use Test::More;

{
    package DirectPlainHashIntegerMethod;
    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }
}

my $plain = bless { x => 1, y => 2 }, 'DirectPlainHashIntegerMethod';
is($plain->add(3), 9, 'plain native-integer method update');
is_deeply($plain, { x => 4, y => 5 }, 'plain method retains both updated slots');

{
    package DirectPlainHashIntegerMethod::Tie;
    sub TIEHASH { bless { values => { x => 1, y => 2 }, stores => 0 }, shift }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub STORE { $_[0]{stores}++; $_[0]{values}{$_[1]} = $_[2] }
    sub stores { $_[0]{stores} }
}

tie my %tied, 'DirectPlainHashIntegerMethod::Tie';
my $tied = bless \%tied, 'DirectPlainHashIntegerMethod';
is($tied->add(2), 7, 'tied hash receiver retains ordinary method semantics');
ok((tied(%tied))->stores >= 2, 'tied receiver performed its STORE callbacks');

{
    package DirectPlainHashIntegerMethod::Number;
    use overload '0+' => sub { $_[0]{value} }, fallback => 1;
}

my $overloaded = bless { x => 1, y => 2 }, 'DirectPlainHashIntegerMethod';
my $number = bless { value => 4 }, 'DirectPlainHashIntegerMethod::Number';
is($overloaded->add($number), 11, 'overloaded argument retains ordinary numeric dispatch');

done_testing;
