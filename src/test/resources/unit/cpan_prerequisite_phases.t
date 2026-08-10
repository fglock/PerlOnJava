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
    require './src/main/perl/lib/PerlOnJava/Process.pm';
    $INC{'PerlOnJava/Process.pm'} = './src/main/perl/lib/PerlOnJava/Process.pm';
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

is_deeply(
    [ CPAN::Distribution::_perlonjava_missing_modules_from_test_output(<<'OUTPUT') ],
Can't locate XML/SAX/DocumentLocator.pm in @INC (you may need to install the XML::SAX::DocumentLocator module)
Compilation failed in require
Can't locate XML/NamespaceSupport.pm in @INC (you may need to install the XML::NamespaceSupport module)
Can't locate XML/SAX/DocumentLocator.pm in @INC (duplicate)
OUTPUT
    [ qw(XML::SAX::DocumentLocator XML::NamespaceSupport) ],
    'canonical missing-module diagnostics are deduplicated and normalized',
);

is_deeply(
    [ CPAN::Distribution::_perlonjava_missing_modules_from_test_output(<<'OUTPUT') ],
Could not load Optional::Feature
Can't locate malformed-name.pm in @INC
not ok 1 - ordinary test failure
OUTPUT
    [],
    'noncanonical test failures do not trigger dependency discovery',
);

{
    my %process_args;
    my ($capture_ok, $captured_output) =
        CPAN::Distribution::_perlonjava_capture_test_command(
            'test command',
            sub {
                %process_args = @_;
                return {
                    exit_code => 0,
                    output => "cpan-live-marker\n",
                    timed_out => 0,
                    error => '',
                };
            },
        );
    ok($capture_ok, 'retry-aware CPAN test command preserves successful status');
    is($captured_output, "cpan-live-marker\n",
        'retry-aware CPAN test command retains output for prerequisite analysis');
    ok($process_args{tee},
        'retry-aware CPAN test command requests live output streaming');
}

{
    local $CPAN::CurrentCommandId = 42;
    my $retry_dist = bless {}, 'CPAN::Distribution';
    ok($retry_dist->_perlonjava_missing_module_retry_available,
        'missing-module retry starts available for a CPAN command');
    $retry_dist->{perlonjava_missing_module_retry_command} = 42;
    ok(!$retry_dist->_perlonjava_missing_module_retry_available,
        'missing-module retry is bounded to one attempt per CPAN command');
}

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
