use strict;
use warnings;
use Config;
use Test::More;

my $is_perlonjava = $Config{archname} =~ /^java-/;
if ($is_perlonjava) {
    require PerlOnJava::ProviderManifest;
} else {
    my $path = './src/main/perl/lib/PerlOnJava/ProviderManifest.pm';
    require $path;
    $INC{'PerlOnJava/ProviderManifest.pm'} = $path;
}
ok(PerlOnJava::ProviderManifest->can('provider_for'), 'loaded provider manifest');

my @providers = PerlOnJava::ProviderManifest->providers;
is(scalar(@providers), 12, 'bundled-provider manifest has twelve module entries');

my %expected = (
    DBI                  => [ '1.643',  'bundled-perl' ],
    Moose                => [ '2.4000', 'compatibility-shim' ],
    'Crypt::PRNG'        => [ '0.089',  'compatibility-shim' ],
    'HTML::Parser'       => [ '3.83',   'java-xs' ],
    'HTML::Entities'     => [ '3.83',   'java-xs' ],
    'XML::LibXML'        => [ '2.0210', 'java-xs' ],
    'XML::LibXSLT'       => [ '2.003000', 'java-xs' ],
    'Set::Object'        => [ '1.43',   'compatibility-shim' ],
    'Package::Stash::XS' => [ '0.30',   'compatibility-shim' ],
    'Scalar::Util'       => [ '1.70',   'java-xs' ],
    'List::Util'         => [ '1.70',   'java-xs' ],
    'Sub::Util'          => [ '1.70',   'java-xs' ],
);

for my $module (sort keys %expected) {
    my $provider = PerlOnJava::ProviderManifest->provider_for($module);
    ok($provider, "$module has a provider entry");
    is($provider->{version}, $expected{$module}[0], "$module provider version");
    is($provider->{provider}, $expected{$module}[1], "$module provider type");
    is($provider->{shadow_policy}, 'forbidden', "$module cannot be shadowed");
}

is_deeply(
    PerlOnJava::ProviderManifest->provider_for('XML::LibXML')->{requires},
    { 'XML::NamespaceSupport' => '0' },
    'bundled XML provider declares its additional runtime dependency',
);

SKIP: {
    skip 'CPAN integration and provider runtime smoke are PerlOnJava-specific', 38
        unless $is_perlonjava;

    require CPAN::Module;
    {
        package Local::ProviderFrontend;
        sub myprint { $_[0]{output} .= $_[1] }
    }
    {
        package Local::ProviderModule;
        our @ISA = qw(CPAN::Module);
        sub id { $_[0]{ID} }
    }

    my $frontend = bless {}, 'Local::ProviderFrontend';
    local $CPAN::Frontend = $frontend;
    my $dbi = bless { ID => 'DBI', force_update => 1 }, 'Local::ProviderModule';
    is($dbi->inst_version, '1.643', 'CPAN sees the bundled provider version');
    ok($dbi->uptodate, 'authoritative provider is not compared with newer CPAN releases');
    ok($dbi->install, 'provider install request is handled without fetching CPAN');
    ok(!exists($dbi->{force_update}), 'force install cannot bypass shadow protection');
    like($frontend->{output}, qr/refusing a shadowing install/, 'shadow refusal is explicit');

    {
        no warnings 'redefine';
        local $ENV{PERLONJAVA_PROVIDER_CONFORMANCE} = 1;
        local *Local::ProviderModule::rematein = sub { return 1 };
        ok($dbi->test, 'explicit provider conformance follows the test-only path');
        like($frontend->{output}, qr/Testing upstream DBI conformance.*installation remains disabled/s,
            'conformance mode states its non-installing policy');
    }

    require CPAN::Distribution;
    require CPAN::Meta::Requirements;
    my $requirements = CPAN::Meta::Requirements->new;
    $requirements->add_minimum('DBI', '1.600');
    my ($status, $provider) = CPAN::Distribution::_perlonjava_provider_requirement(
        $dbi, $requirements, 'DBI');
    is($status, 1, 'compatible provider satisfies a dependency');
    is($provider->{module}, 'DBI', 'resolver reports the satisfying provider');

    $requirements = CPAN::Meta::Requirements->new;
    $requirements->add_minimum('DBI', '2.000');
    my ($bad_status, $message) = CPAN::Distribution::_perlonjava_provider_requirement(
        $dbi, $requirements, 'DBI');
    is($bad_status, -1, 'incompatible forbidden provider fails dependency resolution');
    like($message, qr/version 1\.643.*does not satisfy.*shadowing.*forbidden/s,
        'incompatible-provider failure explains the policy');

    $requirements = CPAN::Meta::Requirements->new;
    $requirements->add_minimum('XML::LibXML', '2.0000');
    my $prereq_pm = { requires => { 'XML::LibXML' => '2.0000' } };
    CPAN::Distribution::_perlonjava_expand_provider_requirements(
        $requirements, $prereq_pm);
    ok($requirements->accepts_module('XML::NamespaceSupport', '0.12'),
        'resolver expands a bundled provider runtime dependency');
    ok(exists $prereq_pm->{requires}{'XML::NamespaceSupport'},
        'expanded provider dependency is classified as a runtime requirement');
    is($prereq_pm->{requires}{'XML::NamespaceSupport'}, '0',
        'expanded provider dependency retains its minimum version');

    SKIP: {
        skip 'full provider loading is covered by the JVM smoke gate', 24
            if $ENV{JPERL_INTERPRETER};
        for my $module (sort keys %expected) {
            my $loaded = eval "require $module; 1";
            ok($loaded, "$module loads from a clean bundled runtime") or diag $@;
            no strict 'refs';
            my $version_name = $module . '::VERSION';
            is("${$version_name}", $expected{$module}[0],
                "$module runtime version matches the manifest");
        }
    }
}

done_testing;
