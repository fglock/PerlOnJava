use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_eval_die.t — DESTROY fires during die/eval exception unwinding
#
# When die throws inside eval{}, lexical variables between the die point and
# the eval boundary go out of scope. Their DESTROY methods must fire during
# the unwinding, before control resumes after the eval block.
# =============================================================================

# Helper class: Guard calls a callback in DESTROY
{
    package Guard;
    sub new {
        my ($class, $cb) = @_;
        return bless { cb => $cb }, $class;
    }
    sub DESTROY {
        my $self = shift;
        $self->{cb}->() if $self->{cb};
    }
}

# --- DESTROY fires when die unwinds through eval ---
{
    my $destroyed = 0;
    eval {
        my $guard = Guard->new(sub { $destroyed++ });
        die "test error";
    };
    is($destroyed, 1, "DESTROY fires when die unwinds through eval");
    like($@, qr/test error/, '$@ set correctly after die in eval with DESTROY');
}

# --- DESTROY fires for nested scopes inside eval ---
{
    my $destroyed = 0;
    eval {
        my $g1 = Guard->new(sub { $destroyed++ });
        {
            my $g2 = Guard->new(sub { $destroyed++ });
            die "nested error";
        }
    };
    is($destroyed, 2, "DESTROY fires for all objects in nested scopes during die");
}

# --- DESTROY fires in LIFO order ---
{
    my @order;
    eval {
        my $g1 = Guard->new(sub { push @order, 'first' });
        my $g2 = Guard->new(sub { push @order, 'second' });
        die "order test";
    };
    is_deeply(\@order, ['second', 'first'],
        "DESTROY fires in LIFO order during eval/die unwinding");
}

# --- $@ is preserved across DESTROY ---
{
    my $destroyed = 0;
    eval {
        my $guard = Guard->new(sub { $destroyed++ });
        die "specific error\n";
    };
    is($@, "specific error\n", '$@ preserved across DESTROY during eval/die');
    is($destroyed, 1, "DESTROY fired during eval/die with specific error");
}

# --- Nested eval: inner die only cleans inner scope ---
{
    my $inner_destroyed = 0;
    my $outer_destroyed = 0;
    eval {
        my $outer_guard = Guard->new(sub { $outer_destroyed++ });
        eval {
            my $inner_guard = Guard->new(sub { $inner_destroyed++ });
            die "inner error";
        };
        is($inner_destroyed, 1, "inner DESTROY fires when inner eval catches");
        is($outer_destroyed, 0, "outer guard NOT destroyed by inner die");
    };
}

# --- DESTROY in eval doesn't affect $@ from die ---
{
    my @events;
    {
        package EventTracker;
        sub new {
            my ($class, $name, $log) = @_;
            bless { name => $name, log => $log }, $class;
        }
        sub DESTROY {
            my $self = shift;
            push @{$self->{log}}, "DESTROY:" . $self->{name};
        }
    }
    eval {
        my $t1 = EventTracker->new("t1", \@events);
        my $t2 = EventTracker->new("t2", \@events);
        die "tracker error";
    };
    like($@, qr/tracker error/, '$@ correct after DESTROY with event tracking');
    # Both should be destroyed
    my $destroy_count = grep { /^DESTROY:/ } @events;
    is($destroy_count, 2, "both objects destroyed during eval/die");
}

done_testing();
