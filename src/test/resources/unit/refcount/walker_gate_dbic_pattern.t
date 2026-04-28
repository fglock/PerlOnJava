use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# walker_gate_dbic_pattern.t — Replicates the DBIC schema/source pattern
# that triggered the walker-gated destroy regression in PR #572.
#
# The pattern: a script-level `my $schema = ...` holds a strong ref to a
# blessed object. Other blessed objects hold WEAK refs back to the schema.
# A bunch of intermediate work happens (method calls, hash element stores,
# anonymous subs that close over $schema). The schema must NOT be DESTROY'd
# while $schema is in scope.
#
# Three hypotheses for what could go wrong (see dev/modules/moose_support.md
# "Refcount root-cause analysis"):
#   A. $schema's storage slot gets cleared mid-execution
#   B. There are TWO instances of the same class, the gate fires for the
#      one held only by closures
#   C. The JVM bytecode emits a copy of $schema; the walker sees one but
#      the live ref is on the other
# =============================================================================

# --- Test 1: simple my-var holding blessed with weak back-ref ---
{
    package T1::Schema;
    sub new { bless { sources => {} }, shift }
    sub DESTROY { $main::T1_DESTROYED++ }

    package T1::Source;
    sub new {
        my ($class, $schema, $name) = @_;
        my $self = bless { name => $name }, $class;
        $self->{schema} = $schema;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    package main;

    $main::T1_DESTROYED = 0;
    my $schema = T1::Schema->new;
    my $src = T1::Source->new($schema, "Track");

    # Force mortal flushes by doing many ref-bearing operations
    for (1..10) {
        my @arr = ($schema, $src, $schema);
        my %h = (s => $schema, t => $src);
        my $copy = $schema;
    }

    is($main::T1_DESTROYED, 0, "T1: schema not destroyed while \$schema is in scope");
    ok(defined $src->{schema}, "T1: weak ref to schema is still defined");
    is($src->{schema}, $schema, "T1: weak ref still points to schema");
}

# --- Test 2: closure captures $schema; mortal flush during closure call ---
{
    package T2::Schema;
    sub new { bless { sources => {} }, shift }
    sub DESTROY { $main::T2_DESTROYED++ }

    package T2::Source;
    sub new {
        my ($class, $schema) = @_;
        my $self = bless {}, $class;
        $self->{schema} = $schema;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }
    sub schema {
        my $self = shift;
        die "detached source" unless defined $self->{schema};
        return $self->{schema};
    }

    package main;
    $main::T2_DESTROYED = 0;
    my $schema = T2::Schema->new;
    my $src = T2::Source->new($schema);

    my $closure = sub {
        my $s = $src->schema;        # Should not die
        return $s->{sources};
    };

    for (1..5) {
        my $r = $closure->();
        my @copies = ($schema, $src, $closure);
    }

    ok($main::T2_DESTROYED == 0, "T2: schema survived through closure");
    ok(defined $src->{schema}, "T2: weak ref alive after closure calls");
}

# --- Test 3: deep call chain holding $schema only via outer lexical ---
{
    package T3::Schema;
    sub new { bless { name => 'X' }, shift }
    sub DESTROY { $main::T3_DESTROYED++ }
    sub name { $_[0]->{name} }

    package T3::Source;
    sub new {
        my ($class, $schema) = @_;
        my $self = bless {}, $class;
        $self->{schema} = $schema;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    sub do_work {
        my $self = shift;
        # Many ref operations that would trigger mortal flush
        my $s = $self->{schema};
        die "detached" unless defined $s;
        my $name = $s->name;
        my @clones = (\$name, [$name], { n => $name });
        return $name;
    }

    package main;

    $main::T3_DESTROYED = 0;
    my $schema = T3::Schema->new;
    my $src = T3::Source->new($schema);

    # Run a bunch of iterations
    my $name;
    for (1..20) {
        $name = $src->do_work;
    }

    is($name, 'X', "T3: schema name accessible after many iterations");
    is($main::T3_DESTROYED, 0, "T3: schema not destroyed during work loop");
}

# --- Test 4: mimics DBIC's source registry — schema holds strong refs
#     to sources, sources hold weak refs back to schema ---
{
    package T4::Schema;
    sub new { bless { sources => {} }, shift }
    sub register_source {
        my ($self, $name, $src) = @_;
        $self->{sources}{$name} = $src;
        $src->{schema} = $self;
        Scalar::Util::weaken($src->{schema});
    }
    sub source { $_[0]->{sources}{$_[1]} }
    sub DESTROY { $main::T4_DESTROYED++ }

    package T4::Source;
    sub new { bless { name => $_[1] }, $_[0] }
    sub schema {
        my $self = shift;
        die "detached source '$self->{name}'" unless defined $self->{schema};
        return $self->{schema};
    }

    package main;

    $main::T4_DESTROYED = 0;
    my $schema = T4::Schema->new;
    for my $name (qw(Artist CD Track Owner Schema TwoKeys)) {
        $schema->register_source($name, T4::Source->new($name));
    }

    # Iterate sources, calling schema() on each — this is the DBIC pattern
    for my $name (qw(Artist CD Track Owner Schema TwoKeys)) {
        my $src = $schema->source($name);
        my $s = eval { $src->schema };
        ok(defined $s, "T4: source '$name' still attached to schema");
    }

    is($main::T4_DESTROYED, 0, "T4: schema not destroyed during source iteration");
}


# --- Test 5: precise DBIC pattern: outer my $schema, inner lives_ok-style
#     closure does method chain, schema accessed through weak ref. This is
#     what t/prefetch/incomplete.t looks like under the hood.

# (Restart counters and class table for T5)
{
    package T5::Schema;
    sub new {
        my $class = shift;
        my $self = bless { sources => {} }, $class;
        # Two levels of registration (mimics DBICTest::Schema->compose)
        for my $name (qw(Artist CD Track)) {
            my $src = T5::Source->new($name);
            $self->{sources}{$name} = $src;
            $src->{schema} = $self;
            Scalar::Util::weaken($src->{schema});
        }
        return $self;
    }
    sub source { $_[0]->{sources}{$_[1]} }
    sub resultset {
        my ($self, $name) = @_;
        my $src = $self->source($name);
        return T5::ResultSet->new($src);
    }
    sub DESTROY { $main::T5_DESTROYED++ }

    package T5::Source;
    sub new { bless { name => $_[1] }, $_[0] }
    sub schema {
        my $self = shift;
        die "detached source '$self->{name}'" unless defined $self->{schema};
        return $self->{schema};
    }
    sub name { $_[0]->{name} }

    package T5::ResultSet;
    sub new {
        my ($class, $src) = @_;
        my $self = bless { source => $src }, $class;
        return $self;
    }
    sub source { $_[0]->{source} }
    sub schema { $_[0]->{source}->schema }
    sub search { $_[0] }   # no-op for testing
    sub next  { T5::Row->new($_[0]->{source}) }

    package T5::Row;
    sub new {
        my ($class, $src) = @_;
        my $self = bless { source => $src }, $class;
        Scalar::Util::weaken($self->{source});  # row weak-refs source
        return $self;
    }
    sub artist {
        my $self = shift;
        die "detached row" unless defined $self->{source};
        return $self->{source}->schema->resultset('Artist');
    }

    package main;

    $main::T5_DESTROYED = 0;
    my $schema = T5::Schema->new;

    # Mimics `lives_ok(sub { ... }, 'msg')` pattern
    my $body = sub {
        my $rs = $schema->resultset('CD')->search;
        my $row = $rs->next;
        my $artist_rs = $row->artist;     # this is what dies in DBIC
        return $artist_rs;
    };

    my $r;
    eval { $r = $body->(); 1 } or do {
        fail("T5: closure died: $@");
    };
    ok(defined $r, "T5: lives_ok-style closure returned a result");
    is($main::T5_DESTROYED, 0, "T5: schema not destroyed during closure");
    ok(defined $schema->{sources}{Artist}{schema}, "T5: Artist source still has schema");
    ok(defined $schema->{sources}{CD}{schema},     "T5: CD source still has schema");
    ok(defined $schema->{sources}{Track}{schema},  "T5: Track source still has schema");

    # Repeat to stress-test
    for (1..10) {
        eval { $body->(); 1 } or do {
            fail("T5: iteration $_ died: $@");
            last;
        };
    }
    ok(defined $schema->{sources}{Artist}{schema}, "T5: Artist source still attached after 10 iterations");
    is($main::T5_DESTROYED, 0, "T5: schema not destroyed after 10 iterations");
}

done_testing;
