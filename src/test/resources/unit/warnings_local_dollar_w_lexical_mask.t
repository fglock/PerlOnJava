use strict;
use warnings;
use Test::More tests => 11;

{
    no warnings 'numeric';
    sub quiet_numeric_conversion { return 0 + shift }
}

my @builder_warnings;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @builder_warnings, @_ };

    ok(1, 'named assertion remains quiet under local dollar-W');
    is('left', 'left', 'named equality remains quiet under local dollar-W');
}
is(scalar @builder_warnings, 0,
    'callee lexical no-warnings mask overrides caller local dollar-W');

my @defined_scope_warnings;
my $quiet_numeric;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @defined_scope_warnings, @_ };
    $quiet_numeric = quiet_numeric_conversion('quiet numeric warning');
}
is($quiet_numeric, 0, 'definition-scope conversion retains its result');
is(scalar @defined_scope_warnings, 0,
    'definition-scope no-warnings mask overrides caller local dollar-W');

my $quiet_anon;
{
    no warnings 'numeric';
    $quiet_anon = sub { return 0 + shift };
}
my @anon_scope_warnings;
my $quiet_anon_numeric;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @anon_scope_warnings, @_ };
    $quiet_anon_numeric = $quiet_anon->('quiet anonymous numeric warning');
}
is($quiet_anon_numeric, 0, 'anonymous definition conversion retains its result');
is(scalar @anon_scope_warnings, 0,
    'anonymous definition no-warnings mask overrides caller local dollar-W');

sub quiet_numeric_factory {
    no warnings 'numeric';
    return sub { return 0 + shift };
}
my $quiet_factory_one = quiet_numeric_factory();
my $quiet_factory_two = quiet_numeric_factory();
my @factory_scope_warnings;
my @factory_results;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @factory_scope_warnings, @_ };
    push @factory_results, $quiet_factory_one->('first factory warning');
    push @factory_results, $quiet_factory_two->('second factory warning');
}
is_deeply(\@factory_results, [0, 0],
    'repeated closure instances retain their conversion results');
is(scalar @factory_scope_warnings, 0,
    'repeated closure instances retain generated-class warning metadata');

my @ordinary_warnings;
my $numeric;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @ordinary_warnings, @_ };
    my $label = 'ordinary numeric warning';
    $numeric = 0 + $label;
}
is($numeric, 0, 'ordinary numeric conversion retains its result');
is(scalar @ordinary_warnings, 1,
    'local dollar-W still enables an ordinary unmasked numeric warning');
