use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

{
    package IMSR_Source;

    sub new {
        my ($class, $schema) = @_;
        my $self = bless { schema => $schema }, $class;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    package IMSR_Schema;

    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{source} = IMSR_Source->new($self);
        return $self;
    }

    sub DESTROY { $main::IMSR_DESTROYED++ }
}

sub interpreter_frame_keeps_my_schema {
    my $schema = IMSR_Schema->new;
    my $source = $schema->{source};

    ok defined $source->{schema},
        'interpreter my scalar remains a strong owner';
    is $source->{schema}, $schema,
        'weak backref still points at the live lexical schema';

    return;

    # Force the bytecode interpreter path for this whole subroutine.
    my $f = sub { 1 };
    goto $f;
}

interpreter_frame_keeps_my_schema();
