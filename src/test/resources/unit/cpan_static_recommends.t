use strict;
use warnings;
use Config;
use File::Spec;
use File::Temp qw(tempdir);
use Test::More tests => 1;

require CPAN::Meta;
require CPAN::Meta::Requirements;

my $is_perlonjava = $Config{archname} =~ /^java-/;
if ($is_perlonjava) {
    require CPAN::Distribution;
}
else {
    require './src/main/perl/lib/PerlOnJava/Process.pm';
    $INC{'PerlOnJava/Process.pm'} = './src/main/perl/lib/PerlOnJava/Process.pm';
    require './src/main/perl/lib/CPAN/Distribution.pm';
}

unless (CPAN->can('use_inst')) {
    no strict 'refs';
    *{'CPAN::use_inst'} = sub { 1 };
}

{
    package Local::StaticRecommendMetaRegistry;
    sub has_usable { 1 }
}

{
    package Local::StaticRecommendPrereqs;
    sub requirements_for {
        my ($self, $phase, $relationship) = @_;
        return CPAN::Meta::Requirements->from_string_hash(
            $self->{$phase}{$relationship} || {},
        );
    }
}

{
    package Local::GeneratedMeta;
    sub dynamic_config { 0 }
    sub effective_prereqs { $_[0]{prereqs} }
}

{
    package Local::StaticRecommendDistribution;
    our @ISA = qw(CPAN::Distribution);
    sub read_meta { $_[0]{generated_meta} }
}

my $build_dir = tempdir(CLEANUP => 1);
open my $meta, '>', File::Spec->catfile($build_dir, 'META.json')
    or die "cannot create static META fixture: $!";
print {$meta} <<'META_JSON';
{
  "abstract": "static recommendation fixture",
  "author": ["PerlOnJava"],
  "dynamic_config": true,
  "generated_by": "cpan_static_recommends.t",
  "license": ["perl_5"],
  "meta-spec": {"version": "2", "url": "https://metacpan.org/pod/CPAN::Meta::Spec"},
  "name": "Local-Static-Recommend",
  "prereqs": {
    "runtime": {
      "requires": {"Local::Required": "1"},
      "recommends": {"Local::Recommended": "2"}
    }
  },
  "release_status": "stable",
  "version": "1"
}
META_JSON
close $meta;
open my $makefile, '>', File::Spec->catfile($build_dir, 'Makefile')
    or die "cannot create Makefile fixture: $!";
close $makefile;

my $dist = bless {
    build_dir => $build_dir,
    writemakefile => 1,
    generated_meta => bless({
        prereqs => bless({
            runtime => { requires => { 'Local::Required' => '1' } },
        }, 'Local::StaticRecommendPrereqs'),
    }, 'Local::GeneratedMeta'),
}, 'Local::StaticRecommendDistribution';

no warnings 'once';
local $CPAN::META = bless {}, 'Local::StaticRecommendMetaRegistry';
local $CPAN::Config = { recommends_policy => 1 };
use warnings 'once';

my $prereqs = CPAN::Distribution::prereq_pm($dist);
is_deeply(
    $prereqs->{opt_requires},
    { 'Local::Recommended' => '2' },
    'static META recommendations survive generated metadata precedence',
);
