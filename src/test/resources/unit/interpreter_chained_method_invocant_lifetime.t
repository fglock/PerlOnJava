use strict;
use warnings;
use Test::More tests => 9;

{
    package Local::ChainedFieldHash;
    use Hash::Util::FieldHash qw(fieldhash);

    fieldhash my %first;
    fieldhash my %second;
    our $destroyed = 0;

    sub new { bless \do { my $value }, shift }

    sub init {
        my ($self, %arg) = @_;
        $first{$self} = $arg{first};
        $second{$self} = $arg{second};
        return $self;
    }

    sub args {
        my $self = shift;
        return (first => $first{$self}, second => $second{$self});
    }

    sub DESTROY { $destroyed++ }
}

{
    package Local::ChainedFactory;
    sub new { 'Local::ChainedFieldHash' -> new }
}

{
    package Local::TiedChain;

    sub new {
        my ($class, @path) = @_;
        my %hash;
        tie %hash, $class, @path;
        return \%hash;
    }

    sub TIEHASH {
        my ($class, @path) = @_;
        return bless \@path, $class;
    }

    sub FETCH {
        my ($self, $name) = @_;
        return bless ref($self)->new(@$self, $name), ref($self);
    }

    sub DESTROY { }
}

my %chain;
tie %chain, 'Local::TiedChain';

my $object = Local::ChainedFactory:: -> new -> init(
    first  => 'marker',
    second => $chain{one}{two}{three},
);
my %args = $object->args;
is($args{first}, 'marker',
    'chained temporary remains alive while outer method arguments are evaluated');
is(ref($args{second}), 'Local::TiedChain',
    'fieldhash retains a tied argument evaluated after the chained invocant');
undef $object;
is($Local::ChainedFieldHash::destroyed, 1,
    'completed chain does not retain its temporary invocant');

my $saved = Local::ChainedFactory:: -> new;
my $initialized = $saved -> init(
    first  => 'saved',
    second => $chain{four}{five}{six},
);
my %saved_args = $initialized->args;
is($saved_args{first}, 'saved', 'saved invocant remains unchanged');
is(ref($saved_args{second}), 'Local::TiedChain',
    'saved invocant retains its tied argument');

CHAIN_ARGUMENT: {
    Local::ChainedFactory:: -> new -> init(
        first  => 'never stored',
        second => last CHAIN_ARGUMENT,
    );
}
is($Local::ChainedFieldHash::destroyed, 2,
    'labeled control flow releases the abandoned invocant hold');

my $eval_result = eval {
    Local::ChainedFactory:: -> new -> init(
        first  => 'never stored',
        second => die "argument failed\n",
    );
};
ok(!defined($eval_result), 'argument exception aborts the chained method call');
like($@, qr/^argument failed/, 'argument exception remains catchable by eval');
is($Local::ChainedFieldHash::destroyed, 3,
    'exception unwinding releases the temporary invocant hold');
