use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# =============================================================================
# our_metas_underflow.t — Reproducer for the selective refCount bug:
# storing a blessed reference in a package hash (`our %METAS`) does NOT
# bump the selective refCount, so the object's refCount underflows to 0
# at end-of-statement when the temporary holding it expires.
#
# This is the root cause of the Class::MOP bootstrap failure that the
# walker gate has been working around. See dev/modules/moose_support.md
# section D-W6.7 for the diagnostic trace.
#
# The minimal pattern from CMOP is:
#
#   package Cache;
#   our %METAS;
#   sub store_metaclass_by_name { $METAS{$_[0]} = $_[1] }
#   sub get_metaclass_by_name   { $METAS{$_[0]}         }
#
# A meta-object is constructed once (refCount=1) and stored in %METAS.
# Subsequent statements call `Cache::get_metaclass_by_name($name)` which
# returns the value from the hash. That return value is a temporary
# (mortal); when it expires, refCount drops 1 → 0 unless the hash slot
# storage is itself counted.
#
# When refCount reaches 0, MortalList.flush -> DestroyDispatch.callDestroy
# fires DESTROY (incorrectly) and clears all weak refs to the metaclass.
# This nulls back-refs in attached objects.
#
# Real Perl: %METAS storage is a strong ref. The metaclass is alive for
# the lifetime of %METAS. DESTROY is NOT called until END or %METAS goes
# out of scope.
# =============================================================================

# --- Test 1: Plain object storage in a package hash ---
{
    package T1::Meta;
    sub new { bless { name => $_[1] }, $_[0] }
    sub name { $_[0]->{name} }
    sub DESTROY { $main::T1_DESTROYED++ }

    package T1::Cache;
    our %METAS;
    sub store { $METAS{$_[0]} = $_[1] }
    sub get   { $METAS{$_[0]}         }

    package main;
    $main::T1_DESTROYED = 0;

    {
        my $meta = T1::Meta->new('Foo');
        T1::Cache::store('Foo', $meta);
    }  # $meta lex out of scope; %METAS still has the only strong ref

    is($main::T1_DESTROYED, 0,
       'Test 1: object stored in package hash NOT destroyed when local var falls out of scope');

    # Multiple `get` calls should not destroy it either
    for (1..3) {
        my $m = T1::Cache::get('Foo');
        ok(defined $m, "Test 1: get #$_ returns defined");
        is(ref($m), 'T1::Meta', "Test 1: get #$_ returns blessed object");
    }

    is($main::T1_DESTROYED, 0,
       'Test 1: still alive after multiple gets');
}

# --- Test 2: Method-chain temporary (the exact CMOP pattern) ---
{
    package T2::Meta;
    sub new { bless { name => $_[1], attrs => [] }, $_[0] }
    sub add_attribute {
        my ($self, $attr) = @_;
        push @{ $self->{attrs} }, $attr;
        $attr->{owner} = $self;
        Scalar::Util::weaken($attr->{owner});
        # simulate "after attach" check (this is where CMOP dies):
        die "owner gone for $attr->{name}!\n" unless defined $attr->{owner};
        return $self;
    }
    sub DESTROY { $main::T2_DESTROYED++ }

    package T2::Cache;
    our %METAS;
    sub initialize {
        my ($class, $name) = @_;
        return $METAS{$name} ||= T2::Meta->new($name);
    }

    package T2::Attr;
    sub new { bless { name => $_[1] }, $_[0] }

    package main;
    $main::T2_DESTROYED = 0;

    # Bootstrap the meta:
    T2::Cache::initialize('T2::Bar', 'T2::Bar');

    # Now do the CMOP-style 3-statement add_attribute pattern.
    # Each `T2::Cache::initialize(...)->add_attribute(...)` is a separate
    # statement; the meta-object is held only via %METAS between them.
    eval {
        T2::Cache::initialize('T2::Bar', 'T2::Bar')
          ->add_attribute(T2::Attr->new('one'));
        T2::Cache::initialize('T2::Bar', 'T2::Bar')
          ->add_attribute(T2::Attr->new('two'));
        T2::Cache::initialize('T2::Bar', 'T2::Bar')
          ->add_attribute(T2::Attr->new('three'));
        T2::Cache::initialize('T2::Bar', 'T2::Bar')
          ->add_attribute(T2::Attr->new('four'));
    };
    is($@, '', 'Test 2: no die — owner ref stays alive across all add_attribute calls');
    is($main::T2_DESTROYED, 0,
       'Test 2: meta NOT destroyed during 4-statement bootstrap');
}

# --- Test 3: The walker-gate stress shape ---
# Multiple metas in %METAS, multiple weak refs each, exercised by
# repeated method-chain temporaries.
{
    package T3::Meta;
    sub new { bless { name => $_[1] }, $_[0] }
    sub touch { $_[0]->{count}++ }
    sub DESTROY { $main::T3_DESTROYED{ $_[0]->{name} }++ }

    package T3::Cache;
    our %METAS;
    sub get_or_create {
        $METAS{$_[0]} ||= T3::Meta->new($_[0]);
    }

    package T3::Holder;
    sub new { bless { meta => $_[1] }, $_[0] }
    sub link {
        my ($self, $m) = @_;
        $self->{meta} = $m;
        Scalar::Util::weaken($self->{meta});
    }

    package main;
    %main::T3_DESTROYED = ();

    my @holders;
    for my $name (qw(A B C D E)) {
        my $m = T3::Cache::get_or_create($name);
        my $h = T3::Holder->new($m);
        $h->link($m);
        push @holders, $h;
    }

    # Touch each via method-chain temp from %METAS
    for (1..5) {
        for my $name (qw(A B C D E)) {
            T3::Cache::get_or_create($name)->touch;
        }
    }

    # All metas should still be alive: %METAS holds them,
    # @holders has weak refs.
    for my $name (qw(A B C D E)) {
        is($main::T3_DESTROYED{$name} || 0, 0,
           "Test 3: meta '$name' NOT prematurely destroyed");
    }

    # Each holder's weak ref should still resolve
    for my $h (@holders) {
        ok(defined $h->{meta}, "Test 3: weak back-ref still defined");
    }
}

done_testing();
