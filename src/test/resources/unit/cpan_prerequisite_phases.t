use strict;
use warnings;
use Config;
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

require CPAN::Meta::Requirements;
require CPAN::Meta;

my $is_perlonjava = $Config{archname} =~ /^java-/;
if ($is_perlonjava) {
    require CPAN::Distribution;
    require CPAN::Queue;
} else {
    require './src/main/perl/lib/CPAN/Distribution.pm';
    require './src/main/perl/lib/CPAN/Queue.pm';
}
unless (CPAN->can('use_inst')) {
    no strict 'refs';
    *{'CPAN::use_inst'} = sub { 1 };
}

{
    package Local::PhaseDistribution;
    our @ISA = qw(CPAN::Distribution);
    sub prereq_pm { $_[0]{phase_prereqs} }
    sub configure_requires { $_[0]{configure_prereqs} }
    sub prefs { {} }
    sub _feature_depends { {} }
}
{
    package Local::PhaseMeta;
    sub has_usable { 1 }
    sub has_inst { 1 }
}
{
    package Local::EffectivePrereqs;
    sub requirements_for {
        my ($self, $phase, $relationship) = @_;
        return CPAN::Meta::Requirements->from_string_hash(
            $self->{$phase}{$relationship} || {},
        );
    }
}
{
    package Local::MetadataObject;
    sub dynamic_config { 0 }
    sub effective_prereqs { $_[0]{prereqs} }
}
{
    package Local::MetadataDistribution;
    our @ISA = qw(CPAN::Distribution);
    sub read_meta { $_[0]{metadata} }
}

no warnings 'once';
local $CPAN::META = bless {}, 'Local::PhaseMeta';
use warnings 'once';

my $dist = bless {
    phase_prereqs => {
        requires          => { 'Local::Runtime' => '1' },
        build_requires    => { 'Local::Build'   => '2' },
        test_requires     => { 'Local::Test'    => '3' },
        opt_requires      => {},
        opt_build_requires => {},
        opt_test_requires => {},
    },
    configure_prereqs => { 'Local::Configure' => '4' },
}, 'Local::PhaseDistribution';

my $build_dir = tempdir(CLEANUP => 1);
open my $makefile, '>', File::Spec->catfile($build_dir, 'Makefile')
    or die "cannot create phase test Makefile: $!";
close $makefile;
open my $meta_json, '>', File::Spec->catfile($build_dir, 'META.json')
    or die "cannot create phase test META.json: $!";
print {$meta_json} <<'META_JSON';
{
   "abstract" : "phase metadata fixture",
   "author" : [ "PerlOnJava" ],
   "dynamic_config" : false,
   "generated_by" : "cpan_prerequisite_phases.t",
   "license" : [ "perl_5" ],
   "meta-spec" : {
      "url" : "https://metacpan.org/pod/CPAN::Meta::Spec",
      "version" : "2"
   },
   "name" : "Local-Phase-Metadata",
   "prereqs" : {
      "build" : { "requires" : { "Local::Build" : "2" } },
      "runtime" : { "requires" : { "Local::Runtime" : "1" } },
      "test" : { "requires" : { "Local::Test" : "3" } }
   },
   "release_status" : "stable",
   "version" : "1"
}
META_JSON
close $meta_json;
my $metadata_dist = bless {
    build_dir => $build_dir,
    writemakefile => 1,
    metadata => bless({
        prereqs => bless({
            runtime => { requires => { 'Local::Runtime' => '1' } },
            build   => { requires => { 'Local::Build'   => '2' } },
            test    => { requires => { 'Local::Test'    => '3' } },
        }, 'Local::EffectivePrereqs'),
    }, 'Local::MetadataObject'),
}, 'Local::MetadataDistribution';
no warnings 'once';
local $CPAN::Config = {};
use warnings 'once';
my $metadata_prereqs = CPAN::Distribution::prereq_pm($metadata_dist);
is_deeply($metadata_prereqs->{requires}, { 'Local::Runtime' => '1' },
    'metadata runtime requirements remain runtime requirements');
is_deeply($metadata_prereqs->{build_requires}, { 'Local::Build' => '2' },
    'metadata build requirements remain build requirements');
is_deeply($metadata_prereqs->{test_requires}, { 'Local::Test' => '3' },
    'metadata test requirements are not merged into build requirements');

$metadata_dist->{metadata} = bless({
    prereqs => bless({
        runtime => { requires => { 'Local::Runtime' => '1' } },
        build   => { requires => {
            'Local::Build' => '2',
            'Local::Test'  => '3',
        } },
    }, 'Local::EffectivePrereqs'),
}, 'Local::MetadataObject');
delete $metadata_dist->{prereq_pm};
$metadata_prereqs = CPAN::Distribution::prereq_pm($metadata_dist);
is_deeply($metadata_prereqs->{build_requires}, { 'Local::Build' => '2' },
    'static META removes test-only modules from collapsed MYMETA build requirements');
is_deeply($metadata_prereqs->{test_requires}, { 'Local::Test' => '3' },
    'static META restores test requirements collapsed by MYMETA 1.4');

my ($make_requirements) = $dist->prereqs_for_slot('later');
is_deeply(
    [ sort keys %$make_requirements ],
    [ qw(Local::Build Local::Runtime) ],
    'make boundary includes runtime and build requirements only',
);

my ($test_requirements) = $dist->prereqs_for_slot('test_requires_later');
is_deeply(
    [ sort keys %$test_requirements ],
    [ 'Local::Test' ],
    'test requirements remain isolated until the test boundary',
);

my ($configure_requirements) = $dist->prereqs_for_slot('configure_requires_later');
is_deeply(
    [ sort keys %$configure_requirements ],
    [ 'Local::Configure' ],
    'configure requirements remain in their own phase',
);

is(
    CPAN::Distribution::_perlonjava_prereq_reqtype(
        'later', $dist->{phase_prereqs}, 'Local::Runtime'),
    'r',
    'runtime requirement receives runtime queue type',
);
is(
    CPAN::Distribution::_perlonjava_prereq_reqtype(
        'later', $dist->{phase_prereqs}, 'Local::Build'),
    'b',
    'build requirement receives build queue type',
);
is(
    CPAN::Distribution::_perlonjava_prereq_reqtype(
        'test_requires_later', $dist->{phase_prereqs}, 'Local::Test'),
    't',
    'test requirement receives test queue type',
);
is(
    CPAN::Distribution::_perlonjava_prereq_reqtype(
        'configure_requires_later', {}, 'Local::Configure'),
    'q',
    'configure requirement receives configure queue type',
);

CPAN::Queue->nullify_queue;
CPAN::Queue->jumpqueue(
    { qmod => 'Local::TestParent', reqtype => 't', optional => 0 },
    { qmod => 'Local::RuntimeChild', reqtype => 'r', optional => 0 },
);
is(
    CPAN::Queue->reqtype_of('Local::RuntimeChild'),
    't',
    'runtime dependency inherits a test-only parent phase',
);
CPAN::Queue->nullify_queue;

for my $reqtype (qw(r b t q)) {
    local $ENV{PERLONJAVA_STRICT_DEPENDENCY_TESTING};
    $dist->{reqtype} = $reqtype;
    ok($dist->_perlonjava_skip_dependency_tests,
        "$reqtype dependency skips its own tests by default");
}

$dist->{reqtype} = 'c';
ok(!$dist->_perlonjava_skip_dependency_tests,
    'explicit command target keeps its test surface');
{
    local $ENV{PERLONJAVA_STRICT_DEPENDENCY_TESTING} = 1;
    $dist->{reqtype} = 't';
    ok(!$dist->_perlonjava_skip_dependency_tests,
        'strict dependency-testing mode keeps dependency tests');
}

done_testing;
