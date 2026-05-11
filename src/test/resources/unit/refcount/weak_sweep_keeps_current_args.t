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

sub keep_through_sweep {
    my $weak = $_[0];
    weaken($weak);
    Internals::jperl_gc() if defined &Internals::jperl_gc;
    return $_[0];
}

my $schema = WSKCA_Schema->new;
my $clone = keep_through_sweep($schema->clone);

ok defined $clone->{source}{schema},
    "weak sweep treats current arguments as live roots";
