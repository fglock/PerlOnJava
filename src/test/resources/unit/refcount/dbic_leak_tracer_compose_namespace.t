use strict;
use warnings;
use Test::More tests => 5;
use Scalar::Util qw(weaken);

# DBIx::Class t/52leaks.t installs a global bless hook that immediately stores
# every blessed object in a weak leak-tracing registry. That exposed a lifetime
# bug where a schema returned from compose_namespace() could have its weak
# ResultSource backrefs cleared before the caller received the return value.
my $bless_override;
BEGIN {
    $bless_override = sub {
        return CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    };
    *CORE::GLOBAL::bless = sub { goto $bless_override };
}

my %weak_registry;

sub populate_weakregistry {
    my $target = shift;
    my $addr = "$target";

    for my $key (keys %weak_registry) {
        delete $weak_registry{$key} unless defined $weak_registry{$key}{weakref};
    }

    $weak_registry{$addr}{weakref} = $target
        unless defined $weak_registry{$addr}{weakref};
    weaken($weak_registry{$addr}{weakref});

    return $target;
}

$bless_override = sub {
    my $obj = CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    return populate_weakregistry($obj);
};

{
    package UnitDBIC52::Source;

    sub new {
        my ($class, $from) = @_;
        my $pkg = ref($class) || $class;
        return bless({ %$from }, $pkg) if ref $from;
        return bless({ name => $from }, $pkg);
    }

    sub result_class { $_[0]->{result_class} || 'UnitDBIC52::Row' }

    sub schema {
        my $self = shift;
        $self->{schema} = shift if @_;
        die "detached source '$self->{name}'" unless defined $self->{schema};
        return $self->{schema};
    }

    package UnitDBIC52::CoreSchema;

    our %CLASS_SOURCES = (
        Genre  => UnitDBIC52::Source->new('Genre'),
        Artist => UnitDBIC52::Source->new('Artist'),
    );

    sub source_registrations {
        my $self = shift;
        if (@_) {
            if (ref $self) {
                $self->{source_registrations} = $_[0];
            } else {
                %CLASS_SOURCES = %{ $_[0] };
            }
        }
        return ref $self
            ? ($self->{source_registrations} ||= { %CLASS_SOURCES })
            : \%CLASS_SOURCES;
    }

    sub sources { keys %{ $_[0]->source_registrations } }
    sub source  { $_[0]->source_registrations->{$_[1]} }

    sub clone {
        my $self = shift;
        my $clone = { (ref $self ? %$self : ()) };
        bless $clone, (ref $self || $self);
        $clone->source_registrations({});
        $clone->_copy_state_from($self);
        return $clone;
    }

    sub _copy_state_from {
        my ($self, $from) = @_;
        for my $source_name ($from->sources) {
            my $source = $from->source($source_name);
            my $new = $source->new($source);
            $self->register_extra_source($source_name => $new);
        }
    }

    sub register_extra_source { shift->_register_source(@_) }
    sub register_source       { shift->_register_source(@_) }

    sub _register_source {
        my ($self, $source_name, $source) = @_;
        $source->schema($self);
        Scalar::Util::weaken($source->{schema}) if ref($self);
        $self->source_registrations->{$source_name} = $source;
        return $source;
    }

    sub compose_namespace {
        my ($self, $target) = @_;
        my $schema = $self->clone;

        {
            my @copies = ($schema, $schema, $schema);
            my %copies = (schema => $schema, again => $schema);
        }

        $schema->source_registrations({});

        for my $source_name ($self->sources) {
            my $orig_source = $self->source($source_name);
            my $target_class = "${target}::${source_name}";

            my $new_source = $schema->register_source($source_name, bless
                { %$orig_source, result_class => $target_class },
                ref $orig_source,
            );

            if ($target_class->can('result_source_instance')) {
                $target_class->result_source_instance(bless
                    { %$new_source, schema => ref $new_source->{schema} || $new_source->{schema} },
                    ref $new_source,
                );
            }
        }

        return $schema;
    }

    sub DESTROY { $main::UNIT_DBIC52_DESTROYED++ }

    package UnitDBIC52::BaseSchema;
    our @ISA = ('UnitDBIC52::CoreSchema');

    sub clone {
        my $self = UnitDBIC52::CoreSchema::clone(shift, @_);
        main::populate_weakregistry($self);
        return $self;
    }

    package UnitDBIC52::Schema;
    our @ISA = ('UnitDBIC52::BaseSchema');
}

my $schema = UnitDBIC52::Schema->compose_namespace('UnitDBIC52::Composed');
ok($schema, 'schema returned from compose_namespace');

my $genre = $schema->source('Genre');
ok($genre, 'genre source registered on returned schema');
ok(defined $genre->{schema}, 'weak schema backref survives compose_namespace return');

my $resolved = eval { $genre->schema };
my $err = $@;
ok(!$err, 'source schema method does not see a detached source');
is($resolved, $schema, 'source schema backref points at returned schema');
