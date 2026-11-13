use strict;
use warnings;
use B ();
use Test::More tests => 7;
use Scalar::Util qw(weaken isweak);

my %weak;
my $row;
{
    package Local::DBICRescue::Schema;

    sub source_registrations { $_[0]->{source_registrations} }

    sub DESTROY {
        my $self = shift;
        my $sources = $self->source_registrations;
        for my $name (keys %$sources) {
            if (ref $sources->{$name}
                && B::svref_2object($sources->{$name})->REFCNT > 1) {
                $sources->{$name}->schema($self);
                Scalar::Util::weaken($sources->{$name});
                last;
            }
        }
    }

    package Local::DBICRescue::ResultSource;

    sub schema {
        $_[0]->{schema} = $_[1] if @_ > 1;
        return $_[0]->{schema};
    }

    sub DESTROY {
        return if !ref $_[0]->{schema} || Scalar::Util::isweak($_[0]->{schema});

        Scalar::Util::weaken($_[0]->{schema});
        if ($_[0]->{schema}) {
            my $sources = $_[0]->{schema}->source_registrations;
            for (keys %$sources) {
                $sources->{$_} = $_[0]
                    if $sources->{$_} && $sources->{$_} == $_[0];
            }
        }
    }
}

package main;

{
    my $schema = bless { source_registrations => {} }, 'Local::DBICRescue::Schema';
    my $source = bless { schema => $schema }, 'Local::DBICRescue::ResultSource';
    weaken($source->{schema});
    $schema->{source_registrations}{Artist} = $source;
    $row = bless { result_source => $source }, 'Local::DBICRescue::Row';

    $weak{schema} = $schema;
    weaken($weak{schema});
    $weak{source} = $source;
    weaken($weak{source});
    $weak{row} = $row;
    weaken($weak{row});
}

sub diagnose_if_live {
    my ($name) = @_;
    return unless defined $weak{$name};
    return unless defined &Internals::jperl_owner_trace;
    diag("$name remains live: " . Internals::jperl_owner_trace($weak{$name}));
}

ok(defined $weak{schema}, 'ResultSource rescue keeps Schema alive while a row owns it')
    or diagnose_if_live('schema');
ok(defined $weak{source}, 'the row keeps its ResultSource alive')
    or diagnose_if_live('source');
ok(defined $weak{row}, 'the external row remains live before release');

undef $row;

ok(!defined $weak{row}, 'the row is released');
ok(!defined $weak{source}, 'ResultSource is released after its last row owner leaves')
    or diagnose_if_live('source');
ok(!defined $weak{schema}, 'rescued Schema is released after its ResultSource leaves')
    or diagnose_if_live('schema');
ok(!defined $weak{source} && !defined $weak{schema},
    'the destructor handoff does not leave a detached source or Schema behind');
