use strict;
use warnings;
no warnings 'once';
use Config;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'tests PerlOnJava CPAN test environment setup'
    unless $Config{archname} =~ /^java-/;

require CPAN;
require CPAN::Distribution;

{
    package Local::CPANFrontend;
    sub myprint { }
    sub optprint { }
    sub mywarn { }
}

{
    package Local::TestDistribution;
    our @ISA = qw(CPAN::Distribution);
    sub pre_test { }
    sub make { 1 }
    sub shortcut_test { undef }
    sub _perlonjava_skip_dependency_tests { 0 }
    sub satisfy_test_requires {
        my ($self) = @_;
        $CPAN::META->is_tested($self->{dependency_dir}, 123);
        return 1;
    }
    sub dir { $_[0]->{build_dir} }
    sub prefs { $_[0]->{prefs} }
    sub post_test { chdir $main::ORIGINAL_DIR or die "Cannot restore test cwd: $!" }
    sub store_persistent_state { }
    sub success { 1 }
}

my $root = tempdir(CLEANUP => 1);
our $ORIGINAL_DIR = Internals::getcwd();
my $target = "$root/Target-Dist";
my $dependency = "$root/Test-Dependency";
make_path("$target/blib", "$dependency/blib/lib", "$dependency/blib/arch");

local $CPAN::Frontend = bless {}, 'Local::CPANFrontend';
local $CPAN::META = bless { is_tested => {} }, 'CPAN';
local $ENV{PERL5LIB} = '';

my $dist = bless {
    build_dir => $target,
    dependency_dir => $dependency,
    prefs => { test => { commandline => 'PERLONJAVA_SKIP' } },
}, 'Local::TestDistribution';

$dist->test;

open my $manifest, '<', "$target/blib/.perlonjava-cpan-perl5lib"
    or die "Cannot read test PERL5LIB manifest: $!";
my $perl5lib = do { local $/; <$manifest> };
close $manifest;

my %paths = map { $_ => 1 } split /\Q$Config{path_sep}\E/, $perl5lib;
ok($paths{"$dependency/blib/lib"},
    'test prerequisite blib/lib is captured after prerequisite resolution');
ok($paths{"$dependency/blib/arch"},
    'test prerequisite blib/arch is captured after prerequisite resolution');

done_testing;
