use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More tests => 5;

{
    package Local::Source;

    sub schema {
        my ($self, $value) = @_;
        $self->{schema} = $value if @_ > 1;
        return $self->{schema};
    }

    package Local::Schema;

    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        my $source = bless {}, 'Local::Source';
        $self->{source} = $source;
        $source->{schema} = $self;
        Scalar::Util::weaken($source->{schema});
        return $self;
    }

    # DBIx::Class::Schema hands itself to an externally retained source during
    # destruction, then weakens its own source slot. Once the destructor's
    # temporary source reference is released, both objects are collectible.
    our $destroyed = 0;
    sub DESTROY {
        my $self = shift;
        return if $self->{destroyed}++;
        my $source = $self->{source};
        $source->schema($self);
        Scalar::Util::weaken($self->{source});
        $destroyed++;
    }

    package DBICTest::Util::LeakTracer;

    sub assert_empty_weakregistry {
        my ($registry, $quiet) = @_;
        return 0 if defined($registry->{schema}{weakref})
            && !Scalar::Util::isweak($registry->{schema}{weakref});
        return $quiet && !defined($registry->{schema}{weakref});
    }
}

my $registry = {};
{
    my $schema = Local::Schema->new;
    $registry->{schema}{weakref} = $schema;
    weaken($registry->{schema}{weakref});
    ok isweak($registry->{schema}{weakref}),
        'leak registry stores a weak schema reference';
    ok defined($registry->{schema}{weakref}),
        'schema remains live inside its lexical scope';
}

is $Local::Schema::destroyed, 1,
    'schema destructor performs the DBIx source handoff';
ok DBICTest::Util::LeakTracer::assert_empty_weakregistry($registry, 'quiet'),
    'quiet one-entry registry releases destructor handoff temporaries';
ok !defined($registry->{schema}{weakref}),
    'schema is absent from the quiet leak registry';
