use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr);

{
    package DirectMethodHashUpdateGuard;
    sub new { bless { x => 1, y => 2 }, shift }
    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }
}

my $plain = DirectMethodHashUpdateGuard->new;
is($plain->add(3), 9, 'plain two-field method updates both native values');
is($plain->add(4), 17, 'plain method preserves the mutated hash slots');

{
    package DirectMethodHashUpdateTied;
    sub TIEHASH { bless { store => { x => 1, y => 2 }, fetches => 0, stores => 0 }, shift }
    sub FETCH { ++$_[0]{fetches}; $_[0]{store}{$_[1]} }
    sub STORE { ++$_[0]{stores}; $_[0]{store}{$_[1]} = $_[2] }
    sub counts { ($_[0]{fetches}, $_[0]{stores}) }
}

my %tied;
my $tie = tie %tied, 'DirectMethodHashUpdateTied';
my $tied = bless \%tied, 'DirectMethodHashUpdateGuard';
is($tied->add(3), 9, 'tied hash uses ordinary FETCH and STORE semantics');
my ($fetches, $stores) = $tie->counts;
cmp_ok($fetches, '>=', 4, 'tied method fetches both entries for update and return');
cmp_ok($stores, '>=', 2, 'tied method stores both compound updates');

{
    package DirectMethodHashUpdateOverload;
    our %BACKING;
    use overload '%{}' => sub { $BACKING{Scalar::Util::refaddr($_[0])} }, fallback => 1;
    sub new {
        my $value = 0;
        my $self = bless \$value, shift;
        $BACKING{Scalar::Util::refaddr($self)} = { x => 1, y => 2 };
        return $self;
    }
    sub add { DirectMethodHashUpdateGuard::add(@_) }
}

my $overloaded = DirectMethodHashUpdateOverload->new;
is($overloaded->add(3), 9, 'hash dereference overload remains observable');
is($overloaded->add(4), 17, 'overloaded receiver retains its backing values');

done_testing;
