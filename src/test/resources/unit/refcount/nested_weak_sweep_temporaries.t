use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);
use Sub::Quote qw(quote_sub quoted_from_sub);

sub build_quoted_constructor_after_nested_weaken {
    my $sub = quote_sub(
        'NestedWeakSweepQuoted::new',
        q{ my $marker = "nested weak sweep metadata survives"; 42 },
    );

    my $probe = {};
    my $weak = $probe;
    weaken($weak);

    return $sub;
}

my $quoted = build_quoted_constructor_after_nested_weaken();
is(NestedWeakSweepQuoted->new, 42,
    'quoted constructor runs after nested weak sweep request');

my $quoted_info = quoted_from_sub(NestedWeakSweepQuoted->can('new'));
ok($quoted_info, 'quoted constructor metadata survives nested weak sweep request');
like($quoted_info->[1], qr/nested weak sweep metadata survives/,
    'quoted constructor source survives after undefer');

{
    package NestedWeakSweepSource;

    sub new {
        my ($class, $schema) = @_;
        my $self = bless { schema => $schema }, $class;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    sub schema_live {
        defined $_[0]->{schema};
    }

    package NestedWeakSweepSchema;

    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{source} = NestedWeakSweepSource->new($self);
        return $self;
    }

    sub source {
        $_[0]->{source};
    }
}

sub make_nested_weak_sweep_schema {
    NestedWeakSweepSchema->new;
}

ok(make_nested_weak_sweep_schema()->source->schema_live,
    'temporary object survives chained call after nested weak sweep request');
