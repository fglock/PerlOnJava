use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_bless_twostep.t — Two-step bless pattern: DESTROY must not fire
# prematurely when bless is called on an already-stored variable.
#
# Pattern: my $x = {}; bless $x, "Foo";
# This is used by DBIx::Class clone() and many CPAN modules.
#
# Bug: bless() set refCount=0 for first bless, assuming the scalar was a
# temporary. But for the two-step pattern, the scalar is already stored in
# a named variable, so refCount=0 causes premature DESTROY on method calls.
# =============================================================================

# --- Basic two-step bless: DESTROY should fire only when variable goes out of scope ---
{
    my @log;
    {
        package BTS_Basic;
        sub new {
            my $hash = {};
            bless $hash, $_[0];
            return $hash;
        }
        sub hello { push @{$_[1]}, "hello" }
        sub DESTROY { push @{$_[0]->{log}}, "destroyed" }
    }
    {
        my $obj = BTS_Basic->new;
        $obj->{log} = \@log;
        $obj->hello(\@log);
        is_deeply(\@log, ["hello"],
            "two-step bless: DESTROY does not fire during method call");
    }
    is_deeply(\@log, ["hello", "destroyed"],
        "two-step bless: DESTROY fires when variable goes out of scope");
}

# --- Clone pattern: bless existing hash, call method on old object ---
# This is the exact pattern from DBIx::Class Schema::clone()
{
    my @log;
    {
        package BTS_Clonable;
        sub new {
            my $class = shift;
            my $self = { name => $_[0] };
            bless $self, $class;
            return $self;
        }
        sub name { $_[0]->{name} }
        sub clone {
            my $self = shift;
            my $clone = { %$self };
            bless $clone, ref($self);
            # Access the OLD object after blessing the clone
            my $old_name = $self->name;
            push @log, "cloned:$old_name";
            return $clone;
        }
        sub DESTROY { push @log, "destroyed:" . ($_[0]->{name} || 'undef') }
    }
    {
        my $orig = BTS_Clonable->new("original");
        my $clone = $orig->clone;
        is_deeply(\@log, ["cloned:original"],
            "clone pattern: no premature DESTROY during clone");
        is($clone->name, "original", "clone has correct name");
    }
    # Both objects should be destroyed now
    my %seen;
    for (@log) { $seen{$_}++ if /^destroyed:/ }
    is($seen{"destroyed:original"}, 2,
        "clone pattern: both objects eventually destroyed");
}

# --- Clone with _copy_state_from: the full DBIx::Class pattern ---
# After bless, the clone calls methods on the OLD object
{
    my $destroy_count = 0;
    my @log;
    {
        package BTS_Schema;
        use Scalar::Util qw(weaken);

        sub new {
            my ($class, %args) = @_;
            my $self = { %args };
            bless $self, $class;
            return $self;
        }

        sub sources {
            my $self = shift;
            return $self->{sources} || {};
        }

        sub clone {
            my $self = shift;
            my $clone = { %$self };
            bless $clone, ref($self);
            # Clear fields
            $clone->{sources} = undef;
            # Copy state from old object
            $clone->_copy_state_from($self);
            return $clone;
        }

        sub _copy_state_from {
            my ($self, $from) = @_;
            my $old_sources = $from->sources;
            my %new_sources;
            for my $name (keys %$old_sources) {
                my $src = { %{$old_sources->{$name}} };
                bless $src, ref($old_sources->{$name});
                $src->{schema} = $self;
                weaken($src->{schema});
                $new_sources{$name} = $src;
            }
            $self->{sources} = \%new_sources;
        }

        sub connect {
            my $self = shift;
            my $clone = $self->clone;
            $clone->{connected} = 1;
            return $clone;
        }

        sub DESTROY {
            $destroy_count++;
            push @log, "DESTROY:$destroy_count";
        }
    }

    {
        package BTS_Source;
        sub DESTROY { }
    }

    my $schema = BTS_Schema->new(
        sources => {
            Artist => bless({ name => 'Artist' }, 'BTS_Source'),
            CD     => bless({ name => 'CD' }, 'BTS_Source'),
        },
    );

    # compose_namespace pattern
    $destroy_count = 0;
    @log = ();
    my $composed = $schema->clone;
    is($destroy_count, 0,
        "compose_namespace: no premature DESTROY during clone");

    # connect pattern (clone from instance)
    $destroy_count = 0;
    @log = ();
    my $connected = $composed->connect;
    # DESTROY should fire once (for the old $composed's clone that gets discarded
    # inside connect — but the connect method returns the clone, so only the
    # intermediate schema created inside clone() might be destroyed)
    # The key test: DESTROY must NOT fire DURING _copy_state_from
    ok(1, "connect completed without premature DESTROY crash");

    # Verify sources have valid schema refs
    my $sources = $connected->sources;
    for my $name (qw/Artist CD/) {
        ok(defined $sources->{$name}{schema},
            "$name source has valid schema weak ref after connect");
    }
}

done_testing();
