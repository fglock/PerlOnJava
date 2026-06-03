use strict;
use warnings;
use Test::More;

use CPAN::Distribution;

plan skip_all => 'CPAN::Distribution fallback helpers unavailable'
    unless CPAN::Distribution->can('_perlonjava_fallback_pl_args_from_meta_struct')
        && CPAN::Distribution->can('_perlonjava_fallback_makefile_pl');

my $args = CPAN::Distribution->_perlonjava_fallback_pl_args_from_meta_struct({
    name               => 'LWP-Online',
    module_name        => 'LWP::Online',
    version            => '1.08',
    requires           => {
        'LWP::Simple' => '5.805',
        'URI'         => '1.35',
        perl          => '5.005',
    },
    build_requires     => {
        'Test::More' => '0.42',
    },
    configure_requires => {
        'ExtUtils::MakeMaker' => '6.42',
    },
});

is($args->{NAME}, 'LWP::Online', 'fallback uses module_name when present');
is($args->{VERSION}, '1.08', 'fallback preserves version');
is_deeply(
    $args->{PREREQ_PM},
    {
        'LWP::Simple' => '5.805',
        'URI'         => '1.35',
    },
    'fallback maps runtime requires to PREREQ_PM and skips perl',
);
is_deeply(
    $args->{BUILD_REQUIRES},
    { 'Test::More' => '0.42' },
    'fallback preserves build requires',
);
is_deeply(
    $args->{CONFIGURE_REQUIRES},
    { 'ExtUtils::MakeMaker' => '6.42' },
    'fallback preserves configure requires',
);

my $makefile_pl = CPAN::Distribution->_perlonjava_fallback_makefile_pl($args);

like($makefile_pl, qr/NAME\s+=> 'LWP::Online'/, 'generated Makefile.PL names module');
like($makefile_pl, qr/VERSION\s+=> '1\.08'/, 'generated Makefile.PL has version');
like($makefile_pl, qr/PREREQ_PM\s+=> \{/, 'generated Makefile.PL has PREREQ_PM');
like($makefile_pl, qr/'LWP::Simple'\s+=> '5\.805'/, 'generated Makefile.PL has LWP::Simple prereq');
like($makefile_pl, qr/'URI'\s+=> '1\.35'/, 'generated Makefile.PL has URI prereq');
unlike($makefile_pl, qr/'perl'\s+=>/, 'generated Makefile.PL does not emit perl as a module prereq');

my $v2_args = CPAN::Distribution->_perlonjava_fallback_pl_args_from_meta_struct({
    name          => 'Example-Dist',
    x_module_name => 'Example::Dist',
    version       => '2.00',
    prereqs       => {
        runtime => {
            requires => {
                'Runtime::Dep' => '1.0',
            },
        },
        build => {
            requires => {
                'Build::Dep' => '2.0',
            },
        },
        test => {
            requires => {
                'Test::Dep' => '3.0',
            },
        },
        configure => {
            requires => {
                'Configure::Dep' => '4.0',
            },
        },
    },
});

is($v2_args->{NAME}, 'Example::Dist', 'fallback accepts x_module_name');
is_deeply($v2_args->{PREREQ_PM}, { 'Runtime::Dep' => '1.0' }, 'v2 runtime prereqs are mapped');
is_deeply($v2_args->{BUILD_REQUIRES}, { 'Build::Dep' => '2.0' }, 'v2 build prereqs are mapped');
is_deeply($v2_args->{TEST_REQUIRES}, { 'Test::Dep' => '3.0' }, 'v2 test prereqs are mapped');
is_deeply($v2_args->{CONFIGURE_REQUIRES}, { 'Configure::Dep' => '4.0' }, 'v2 configure prereqs are mapped');

done_testing();
