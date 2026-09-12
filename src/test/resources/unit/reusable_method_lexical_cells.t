use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr);

{
    package ReusableMethodLexicalCells;
    sub new { bless { x => 1, y => 2 }, shift }
    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }
}

my $plain = ReusableMethodLexicalCells->new;
is($plain->add(3), 9, 'selected method shape updates both plain slots');
is($plain->add(4), 17, 'later call receives independent lexical copy values');

{
    package ReusableMethodLexicalCellsTied;
    sub TIEHASH { bless { store => { x => 1, y => 2 }, stores => 0 }, shift }
    sub FETCH { $_[0]{store}{$_[1]} }
    sub STORE { ++$_[0]{stores}; $_[0]{store}{$_[1]} = $_[2] }
}

my %tied;
my $tie = tie %tied, 'ReusableMethodLexicalCellsTied';
my $tied = bless \%tied, 'ReusableMethodLexicalCells';
is($tied->add(3), 9, 'selected shape retains tied-hash FETCH and STORE behavior');
cmp_ok($tie->{stores}, '>=', 2, 'tied receiver stores both updates');

{
    package ReusableMethodLexicalCellsOverload;
    our %BACKING;
    use overload '%{}' => sub { $BACKING{Scalar::Util::refaddr($_[0])} }, fallback => 1;
    sub new {
        my $value = 0;
        my $self = bless \$value, shift;
        $BACKING{Scalar::Util::refaddr($self)} = { x => 1, y => 2 };
        return $self;
    }
    sub add { ReusableMethodLexicalCells::add(@_) }
}

my $overloaded = ReusableMethodLexicalCellsOverload->new;
is($overloaded->add(3), 9, 'selected shape preserves hash-dereference overload');
is($overloaded->add(4), 17, 'overloaded receiver retains state across calls');

done_testing;
