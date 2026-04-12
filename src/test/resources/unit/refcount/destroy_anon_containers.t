use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# destroy_anon_containers.t — DESTROY for objects inside anonymous containers
#
# Tests: blessed refs stored in anonymous arrayrefs/hashrefs are properly
# destroyed when the container goes out of scope. This catches the bug where
# RuntimeArray.createReferenceWithTrackedElements() did not birth-track
# anonymous arrays (refCount stayed -1), causing element refCounts to never
# be decremented and DESTROY to never fire.
# =============================================================================

# --- Basic: blessed ref in anonymous arrayref, scope exit ---
{
    my @log;
    {
        package DAC_Basic;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my $arr = [DAC_Basic->new("A")];
    }
    is_deeply(\@log, ["d:A"], "DESTROY fires for object in anon arrayref at scope exit");
}

# --- Blessed ref in anonymous arrayref passed to function ---
{
    my @log;
    {
        package DAC_FuncArg;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dac_take_arr {
        my ($arr) = @_;
        my ($obj) = @$arr;
        return;
    }
    {
        my $obj = DAC_FuncArg->new("B");
        dac_take_arr([$obj, {}]);
    }
    is_deeply(\@log, ["d:B"], "DESTROY fires after func receives anon arrayref with object");
}

# --- Weak ref cleared after anon arrayref with object goes out of scope ---
{
    my @log;
    {
        package DAC_Weak;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $weak;
    {
        my $obj = DAC_Weak->new("C");
        $weak = $obj;
        weaken($weak);
        my $arr = [$obj, "extra"];
    }
    is(defined($weak), '', "weak ref undef after anon arrayref scope exit");
    is_deeply(\@log, ["d:C"], "DESTROY fires when anon arrayref releases last strong ref");
}

# --- Multiple objects in anonymous arrayref ---
{
    my @log;
    {
        package DAC_Multi;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my $arr = [DAC_Multi->new("X"), DAC_Multi->new("Y"), DAC_Multi->new("Z")];
    }
    is(scalar @log, 3, "all three objects destroyed from anon arrayref");
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:X"}, "object X destroyed");
    ok($seen{"d:Y"}, "object Y destroyed");
    ok($seen{"d:Z"}, "object Z destroyed");
}

# --- Anonymous hashref containing blessed object ---
{
    my @log;
    {
        package DAC_Hash;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my $href = { obj => DAC_Hash->new("H") };
    }
    is_deeply(\@log, ["d:H"], "DESTROY fires for object in anon hashref at scope exit");
}

# --- Nested: object inside arrayref inside hashref ---
{
    my @log;
    {
        package DAC_Nested;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my $data = { items => [DAC_Nested->new("N1"), DAC_Nested->new("N2")] };
    }
    is(scalar @log, 2, "both nested objects destroyed");
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:N1"}, "nested object N1 destroyed");
    ok($seen{"d:N2"}, "nested object N2 destroyed");
}

# --- Anon arrayref as function return value, then dropped ---
{
    my @log;
    {
        package DAC_Return;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dac_make_arr {
        return [DAC_Return->new("R")];
    }
    {
        my $arr = dac_make_arr();
    }
    is_deeply(\@log, ["d:R"], "DESTROY fires for object in returned anon arrayref");
}

# --- Weak ref + anon arrayref: object survives while strong ref exists ---
{
    my @log;
    {
        package DAC_Survive;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $weak;
    my $strong;
    {
        $strong = DAC_Survive->new("S");
        $weak = $strong;
        weaken($weak);
        my $arr = [$strong];
    }
    is_deeply(\@log, [], "object survives when strong ref held outside anon arrayref");
    ok(defined($weak), "weak ref still defined while strong ref exists");
    undef $strong;
    is_deeply(\@log, ["d:S"], "DESTROY fires when last strong ref dropped");
    ok(!defined($weak), "weak ref cleared after DESTROY");
}

# --- DBIx::Class pattern: connect_info(\@info) wrapping ---
{
    my @log;
    {
        package DAC_Storage;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dac_connect_info {
        my ($self, $info) = @_;
        # Mimic DBIx::Class pattern: store info then discard
        my @args = @$info;
        return;
    }
    {
        my $schema = DAC_Storage->new("schema");
        dac_connect_info(undef, [$schema]);
    }
    is_deeply(\@log, ["d:schema"],
        "DESTROY fires in DBIx::Class connect_info pattern (object in anon arrayref arg)");
}

# --- Anon arrayref reassignment releases previous contents ---
{
    my @log;
    {
        package DAC_Reassign;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $arr = [DAC_Reassign->new("first")];
    is_deeply(\@log, [], "no DESTROY before reassignment");
    $arr = [DAC_Reassign->new("second")];
    is_deeply(\@log, ["d:first"], "DESTROY fires for first object on reassignment");
    undef $arr;
    is_deeply(\@log, ["d:first", "d:second"], "DESTROY fires for second object on undef");
}

done_testing();
