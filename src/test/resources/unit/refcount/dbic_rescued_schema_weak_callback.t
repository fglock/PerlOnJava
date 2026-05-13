use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken isweak);

# DBIx::Class installs a DBI HandleError callback that weakens the Storage
# object. When user code explicitly undefines the Schema, DBIC may temporarily
# rescue the Schema through a ResultSource during DESTROY. PerlOnJava must still
# clear callback weak refs reachable only through that rescued Schema before the
# next statement.

our $DRC_KEEP_SOURCE;

{
    package DRC_Storage;

    sub new { bless {}, shift }
    sub handle_error { die "handled\n" }
    sub DESTROY {}

    package DRC_Source;

    sub new { bless {}, shift }

    sub schema {
        $_[0]->{schema} = $_[1] if @_ > 1;
        return $_[0]->{schema};
    }

    sub DESTROY {
        return if !ref $_[0]->{schema} || Scalar::Util::isweak($_[0]->{schema});

        Scalar::Util::weaken($_[0]->{schema});
        if ($_[0]->{schema}) {
            my $registrations = $_[0]->{schema}->{source_registrations};
            for my $name (keys %$registrations) {
                $registrations->{$name} = $_[0]
                    if $registrations->{$name} == $_[0];
            }
        }
    }

    package DRC_Schema;

    sub new {
        my $class = shift;
        my $source = DRC_Source->new;
        $main::DRC_KEEP_SOURCE = $source;

        my $self = bless {
            storage              => DRC_Storage->new,
            source_registrations => { Genre => $source },
        }, $class;

        $source->schema($self);
        Scalar::Util::weaken($source->{schema});
        return $self;
    }

    sub storage { $_[0]->{storage} }

    sub DESTROY {
        my $self = shift;
        my $registrations = $self->{source_registrations};

        for my $name (keys %$registrations) {
            next unless ref $registrations->{$name};
            $registrations->{$name}->schema($self);
            Scalar::Util::weaken($registrations->{$name});
            last;
        }
    }
}

my $schema = DRC_Schema->new;
my $handler = do {
    Scalar::Util::weaken(my $weak_self = $schema->storage);
    sub {
        if ($weak_self) {
            $weak_self->handle_error;
        }
        die "unhandled\n";
    };
};

eval { $handler->() };
like $@, qr/^handled/, 'callback sees storage while schema lexical is live';

undef($schema);
eval { $handler->() };
like $@, qr/^unhandled/, 'callback weak self is cleared after explicit schema undef';

undef $DRC_KEEP_SOURCE;
