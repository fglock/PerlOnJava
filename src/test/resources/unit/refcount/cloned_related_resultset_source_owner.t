use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
use B ();

sub dbic_refcount ($) { B::svref_2object($_[0])->REFCNT }

{
    package Issue2043::Source;

    sub new {
        my ($class, $name, $related, $schema) = @_;
        my $self = bless { name => $name, related => $related }, $class;
        $self->{schema} = $schema;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    sub clone_for_schema {
        my ($self, $schema) = @_;
        my $copy = bless { %$self }, ref $self;
        $copy->{schema} = $schema;
        Scalar::Util::weaken($copy->{schema});
        return $copy;
    }

    sub schema {
        $_[0]->{schema} = $_[1] if @_ > 1;
        die "detached source '$_[0]->{name}'" unless $_[0]->{schema};
        $_[0]->{schema};
    }

    sub resultset { Issue2043::ResultSet->new($_[0]) }

    sub related_source {
        my ($self, $relationship) = @_;
        my $schema = $self->schema;
        my $name = $self->{related}{$relationship}
            or die "missing relationship $relationship";
        $schema->{sources}{$name};
    }

    sub DESTROY {
        return unless ref $_[0]->{schema}
            && !Scalar::Util::isweak($_[0]->{schema});
        Scalar::Util::weaken($_[0]->{schema});
        if ($_[0]->{schema}) {
            my $sources = $_[0]->{schema}->{sources};
            for (keys %$sources) {
                $sources->{$_} = $_[0] if $sources->{$_} && $sources->{$_} == $_[0];
            }
        }
    }
}

{
    package Issue2043::Schema;

    sub new {
        my $self = bless { sources => {} }, $_[0];
        $self->{sources}{Artist} = Issue2043::Source->new(
            'Artist', { cds => 'CD' }, $self);
        $self->{sources}{CD} = Issue2043::Source->new(
            'CD', { artist => 'Artist' }, $self);
        $self;
    }

    sub source { $_[0]->{sources}{$_[1]} }
    sub resultset { Issue2043::ResultSet->new($_[0]->source($_[1])) }

    sub clone {
        my $self = shift;
        my $copy = bless { %$self, sources => {} }, ref $self;
        for my $name (keys %{$self->{sources}}) {
            $copy->{sources}{$name} = $self->{sources}{$name}->clone_for_schema($copy);
        }
        $copy;
    }

    sub DESTROY {
        my $self = shift;
        my $sources = $self->{sources};
        for my $name (keys %$sources) {
            my $count = length ref $sources->{$name}
                ? main::dbic_refcount($sources->{$name}) : 0;
            if ($count > 1) {
                $sources->{$name}->schema($self);
                Scalar::Util::weaken($sources->{$name});
                last;
            }
        }
    }
}

{
    package Issue2043::ResultSet;

    sub new { bless { source => $_[1] }, $_[0] }
    sub result_source { $_[0]->{source} }
    sub search { Issue2043::ResultSet->new($_[0]->{source}) }

    sub next {
        my $self = shift;
        $self->{source}->schema;
        Issue2043::Row->new($self->{source});
    }
}

{
    package Issue2043::Row;

    sub new { bless { result_source => $_[1], related_resultsets => {} }, $_[0] }
    sub result_source { $_[0]->{result_source} }

    sub related_resultset {
        my ($self, $relationship) = @_;
        return $self->{related_resultsets}{$relationship}
            if defined $self->{related_resultsets}{$relationship};
        my $source = $self->result_source->related_source($relationship);
        $self->{related_resultsets}{$relationship} =
            Issue2043::ResultSet->new($source);
    }

    sub artist { $_[0]->related_resultset('artist')->next }
    sub search_related { $_[0]->related_resultset($_[1])->search }
}

my $phantom;
my @chain = (
    sub { Issue2043::Schema->new },
    sub { shift->source('Artist') },
    sub { shift->resultset },
    sub { shift->result_source },
    sub { shift->schema },
    sub { shift->resultset('Artist') },
    sub { shift->next },
    sub { shift->result_source },
    sub { shift->schema },
    sub { shift->clone },
    sub { shift->resultset('CD') },
    sub { shift->next },
    sub { shift->artist },
    sub { shift->search_related('cds') },
    sub { shift->next },
);

my $error;
eval {
    for my $index (0 .. $#chain) {
        $phantom = $chain[$index]->($phantom);
    }
    1;
} or $error = $@;

ok(!$error, 'related ResultSet keeps its source attached through the owner transfer')
    or diag($error);
ok($phantom && $phantom->result_source->schema,
    'the returned related row still has an attached source')
    unless $error;

done_testing;
