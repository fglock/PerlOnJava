use strict;
use warnings;
use Test::More tests => 1;
use Scalar::Util qw(weaken);

{
    package WSKCA_Source;
    sub new {
        my ($class, $schema) = @_;
        my $self = bless { schema => $schema }, $class;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    package WSKCA_Schema;
    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{source} = WSKCA_Source->new($self);
        return $self;
    }
    sub clone { WSKCA_Schema->new }
}

sub keep_current_argument_alive {
    my $weak = $_[0];
    weaken($weak);
    return $_[0];
}

my $schema = WSKCA_Schema->new;
my $clone = keep_current_argument_alive($schema->clone);

ok defined $clone->{source}{schema},
    "the current argument owner keeps its schema alive";
