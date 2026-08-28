use strict;
use warnings;
use Test::More;

# Perl's typeglob slots are independent, and `undef` on a typeglob (either
# `undef *Pkg::name` or `undef $Pkg::{name}`) detaches every slot while leaving
# containers that still have a Perl-level reference intact.  Symbol::Util builds
# delete_glob()/delete_sub() on exactly these primitives.

sub slot_states {
    my ($glob_ref) = @_;
    return join ',', map { defined *{$glob_ref}{$_} ? "$_=1" : "$_=0" }
        qw(SCALAR ARRAY HASH CODE IO);
}

# ---------------------------------------------------------------------------
# `undef *Pkg::name` detaches ARRAY/HASH/CODE/IO but keeps the SCALAR slot
# (Perl always reports a SCALAR slot, holding undef).
# ---------------------------------------------------------------------------
{
    package UndefGlobTarget;
    no warnings 'once';
    our $io_data = "io line\n";
    open FOO, '<', \$io_data or die $!;
    *FOO = sub { 'code' };
    our $FOO = 'scalar';
    our @FOO = ('array');
    our %FOO = (hash => 1);
}

is(slot_states(\*UndefGlobTarget::FOO), 'SCALAR=1,ARRAY=1,HASH=1,CODE=1,IO=1',
    'all five slots are populated before undef');

undef *UndefGlobTarget::FOO;

is(slot_states(\*UndefGlobTarget::FOO), 'SCALAR=1,ARRAY=0,HASH=0,CODE=0,IO=0',
    'undef *glob detaches ARRAY, HASH, CODE and IO slots');
ok(!defined $UndefGlobTarget::FOO, 'undef *glob clears the scalar slot value');
is(scalar(@UndefGlobTarget::FOO), 0, 'undef *glob leaves an empty array');
is(scalar(keys %UndefGlobTarget::FOO), 0, 'undef *glob leaves an empty hash');
ok(!eval { &UndefGlobTarget::FOO }, 'undef *glob removes the subroutine');

# ---------------------------------------------------------------------------
# `undef $Pkg::{name}` is the same operation reached through the stash.
# ---------------------------------------------------------------------------
{
    package UndefStashTarget;
    no warnings 'once';
    our $io_data = "io line\n";
    open FOO, '<', \$io_data or die $!;
    *FOO = sub { 'code' };
    our $FOO = 'scalar';
    our @FOO = ('array');
    our %FOO = (hash => 1);
}

is(slot_states(\*UndefStashTarget::FOO), 'SCALAR=1,ARRAY=1,HASH=1,CODE=1,IO=1',
    'stash target starts with all five slots');

undef $UndefStashTarget::{FOO};

is(slot_states(\*UndefStashTarget::FOO), 'SCALAR=1,ARRAY=0,HASH=0,CODE=0,IO=0',
    'undef $Pkg::{name} detaches ARRAY, HASH, CODE and IO slots');
ok(!defined $UndefStashTarget::FOO, 'undef $Pkg::{name} clears the scalar value');
ok(!eval { &UndefStashTarget::FOO }, 'undef $Pkg::{name} removes the subroutine');
ok(!defined *UndefStashTarget::FOO{IO}, 'undef $Pkg::{name} removes the IO slot');

# Assigning undef through the stash hash is a different operation: Perl treats
# it as an undefined value assigned to a typeglob and leaves the slots alone.
{
    package AssignUndefTarget;
    no warnings 'once';
    our $FOO = 'scalar';
    our @FOO = ('array');
}
{
    no warnings;
    $AssignUndefTarget::{FOO} = undef;
}
is($AssignUndefTarget::FOO, 'scalar', '$Pkg::{name} = undef keeps the scalar slot');
is_deeply(\@AssignUndefTarget::FOO, ['array'], '$Pkg::{name} = undef keeps the array slot');

# ---------------------------------------------------------------------------
# Backed-up slots survive the undef and can be re-installed, which is how
# Symbol::Util::delete_glob() deletes a single slot.
# ---------------------------------------------------------------------------
{
    package RoundTripTarget;
    no warnings 'once';
    our $io_data = "io line\n";
    open FOO, '<', \$io_data or die $!;
    *FOO = sub { 'code' };
    our $FOO = 'scalar';
    our @FOO = ('array');
    our %FOO = (hash => 1);
}

my %backup = (
    ARRAY => *RoundTripTarget::FOO{ARRAY},
    HASH  => *RoundTripTarget::FOO{HASH},
    CODE  => *RoundTripTarget::FOO{CODE},
    IO    => *RoundTripTarget::FOO{IO},
);

undef $RoundTripTarget::{FOO};

is_deeply($backup{ARRAY}, ['array'], 'backed-up ARRAY body survives the undef');
is_deeply($backup{HASH}, {hash => 1}, 'backed-up HASH body survives the undef');

{
    no strict 'refs';
    no warnings 'once';
    *RoundTripTarget::FOO = $backup{$_} for qw(ARRAY HASH CODE IO);
}

is(slot_states(\*RoundTripTarget::FOO), 'SCALAR=1,ARRAY=1,HASH=1,CODE=1,IO=1',
    'restoring backed-up slots reproduces the glob');
ok(!defined $RoundTripTarget::FOO, 'the deliberately dropped SCALAR value stays undef');
is_deeply(\@RoundTripTarget::FOO, ['array'], 'restored ARRAY slot keeps its values');
is_deeply(\%RoundTripTarget::FOO, {hash => 1}, 'restored HASH slot keeps its values');
is(eval { &RoundTripTarget::FOO }, 'code', 'restored CODE slot is callable');
is(scalar <RoundTripTarget::FOO>, "io line\n", 'restored IO slot still reads');

# `delete $Pkg::{name}` removes the whole glob from the stash.
delete $RoundTripTarget::{FOO};
ok(!grep({ $_ eq 'FOO' } keys %RoundTripTarget::),
    'delete $Pkg::{name} removes the stash entry');

# ---------------------------------------------------------------------------
# Assigning one slot must not make unrelated slots visible.
# ---------------------------------------------------------------------------
{
    package SlotSource;
    no warnings 'once';
    sub FOO { 'FOO' }
    our $FOO = 'FOO';
}

{
    no strict 'refs';
    no warnings 'once';
    *SlotOnlyScalar::FOO = *SlotSource::FOO{SCALAR};
}
is($SlotOnlyScalar::FOO, 'FOO', 'exporting the SCALAR slot copies the value');
ok(!defined *SlotOnlyScalar::FOO{CODE},
    'exporting the SCALAR slot does not create a phantom CODE slot');
ok(!defined eval { &SlotOnlyScalar::FOO },
    'a glob with only a SCALAR slot is not callable');

{
    no strict 'refs';
    no warnings 'once';
    *SlotOnlyCode::FOO = *SlotSource::FOO{CODE};
}
is(eval { &SlotOnlyCode::FOO }, 'FOO', 'exporting the CODE slot copies the sub');
ok(!defined $SlotOnlyCode::FOO,
    'exporting the CODE slot does not populate the SCALAR slot');

# A slot that was never populated must read back as undef.
ok(!defined *SlotSource::FOO{ARRAY}, '*glob{ARRAY} is undef for an unused slot');
ok(!defined *SlotSource::FOO{HASH}, '*glob{HASH} is undef for an unused slot');
{
    no warnings 'once';
    ok(!defined *NeverUsed::BAR{CODE}, '*glob{CODE} is undef for an unknown symbol');
}

# Merely mentioning a glob must not vivify a CODE slot.
{
    no strict 'refs';
    my $ignored = \*Vivified::BAZ;
}
ok(!defined *Vivified::BAZ{CODE}, 'taking a glob reference creates no CODE slot');
ok(!defined eval { &Vivified::BAZ }, 'a vivified glob is not callable');

# ---------------------------------------------------------------------------
# `defined`/`undef` take-reference mode must not leak into a nested block:
# `&name` inside `defined eval { ... }` is a call, not a code reference.
# ---------------------------------------------------------------------------
our $call_count = 0;
sub counted { $call_count++; return 'called' }

ok(!defined eval { &missing_sub_for_test },
    'defined eval { &missing } sees the die, not a code reference');
ok(defined eval { &counted }, 'defined eval { &existing } sees the return value');
is($call_count, 1, '&name inside defined eval { } is actually called');

# ---------------------------------------------------------------------------
# Requiring a module without a DATA section must not add a DATA stash entry
# to the requiring package.
# ---------------------------------------------------------------------------
{
    package NoDataImport;
    eval q{ require Scalar::Util; 1 } or die $@;
}
ok(!grep({ $_ eq 'DATA' } keys %NoDataImport::),
    'require does not add a phantom DATA entry to the caller package');

done_testing;
