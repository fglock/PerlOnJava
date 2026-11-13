use strict;
use warnings;
use Test::More;
use B qw(svref_2object);
use Scalar::Util qw(weaken);

{
    package Issue2043::RelatedSchema;

    sub DESTROY {
        my $self = shift;
        my $registrations = $self->{source_registrations};

        for my $name (keys %$registrations) {
            if (length ref $registrations->{$name}
                    && B::svref_2object($registrations->{$name})->REFCNT > 1) {
                $registrations->{$name}->schema($self);
                Scalar::Util::weaken($registrations->{$name});
                last;
            }
        }
    }
}

{
    package Issue2043::RelatedSource;

    sub schema {
        $_[0]->{schema} = $_[1] if @_ > 1;
        $_[0]->{schema};
    }

    sub DESTROY {
        return if !ref $_[0]->{schema}
            || Scalar::Util::isweak($_[0]->{schema});

        Scalar::Util::weaken($_[0]->{schema});
        if ($_[0]->{schema}) {
            my $registrations = $_[0]->{schema}->{source_registrations};
            for (keys %$registrations) {
                $registrations->{$_} = $_[0]
                    if $registrations->{$_} == $_[0];
            }
        }
    }
}

{
    package Issue2043::RelatedRow;

    sub new { bless { source => $_[1] }, $_[0] }

    sub related_source {
        my $self = shift;
        my $schema = $self->{source}->schema;
        my $source = $schema->{source_registrations}{B};
        return $source;
    }
}

my $current;
{
    my $schema = bless { source_registrations => {} }, 'Issue2043::RelatedSchema';
    my $source_a = bless { schema => $schema }, 'Issue2043::RelatedSource';
    my $source_b = bless { schema => $schema }, 'Issue2043::RelatedSource';
    weaken($source_a->{schema});
    weaken($source_b->{schema});
    $schema->{source_registrations}{A} = $source_a;
    $schema->{source_registrations}{B} = $source_b;
    $current = $schema;
    undef $source_a;
    undef $source_b;
    undef $schema;
}

$current = Issue2043::RelatedRow->new($current->{source_registrations}{A});
$current = $current->related_source;

ok(ref($current) eq 'Issue2043::RelatedSource',
    'related source survives the row-to-source transfer');
ok(ref($current->{schema}) eq 'Issue2043::RelatedSchema',
    'schema stays attached to the returned related source');

done_testing;
