#!/usr/bin/env perl
# More accurate reproducer of DBIC's t/52leaks.t pattern.
# The schema is NOT kept in a my-lexical. Once $phantom is reassigned
# past it, the schema's reachability depends on whatever global
# structures DBIC's init_schema leaves behind.
use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

unless ($ENV{JPERL_FORCE_SWEEP_EVERY_FLUSH}) {
    plan skip_all => 'set JPERL_FORCE_SWEEP_EVERY_FLUSH=1';
}

package My::Schema {
    our %REGISTRY;   # global — strongly holds every schema we create
    sub new {
        my $class = shift;
        my $self = bless { sources => {}, name => 'main' }, $class;
        $self->{sources}{Artist} = My::ResultSource->new($self, 'Artist');
        $REGISTRY{$self} = $self;        # strong global ref
        return $self;
    }
    sub source { $_[0]->{sources}{$_[1]} }
}

package My::ResultSource {
    use Scalar::Util qw(weaken);
    sub new {
        my ($class, $schema, $name) = @_;
        my $self = bless { schema => $schema, name => $name }, $class;
        weaken $self->{schema};
        return $self;
    }
    sub schema {
        $_[0]->{schema} or die "DETACHED at $_[0]->{name}\n"
    }
    sub resultset {
        my $self = shift;
        bless { source => $self }, 'My::ResultSet';
    }
}

package My::ResultSet {
    sub source { $_[0]->{source} }
    sub schema { $_[0]->source->schema }
}

package main;

# DBIC pattern: chain replaces $phantom each iter; schema only kept
# alive via the global %My::Schema::REGISTRY hash.
my $phantom;
for my $step (
    sub { My::Schema->new },             # creates schema
    sub { shift->source('Artist') },     # $phantom = RS, schema in global
    sub { shift->resultset },            # ← needs schema via weak ref
    sub { shift->source },               # back to RS
    sub { shift->resultset },            # again
    sub { shift->schema },               # FINAL: must dereference schema
    sub { shift->source('Artist') },
    sub { shift->resultset },
) {
    Internals::jperl_gc() if defined &Internals::jperl_gc;
    my $err;
    eval { $phantom = $step->($phantom); 1 } or $err = $@;
    if ($err) {
        diag "FAILURE: $err";
        fail("step failed: $err");
        last;
    }
    pass("step OK; \$phantom now ref=" . (ref($phantom) // 'scalar'));
}

ok( defined $phantom, 'final $phantom defined' );
done_testing;
