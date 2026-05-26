# CPAN Configuration for PerlOnJava
# This provides sensible defaults that work out of the box
# Users can override with ~/.perlonjava/cpan/CPAN/MyConfig.pm

package CPAN::Config;
use strict;
use warnings;
use File::Spec;

# Determine home directory cross-platform
my $home = $ENV{HOME} || $ENV{USERPROFILE} || '.';

# Use .perlonjava/cpan for CPAN data (consistent with PerlOnJava conventions)
my $cpan_home = File::Spec->catdir($home, '.perlonjava', 'cpan');

# Determine OS-specific tools
my $is_windows = $^O eq 'MSWin32' || $^O eq 'cygwin';

# Bootstrap bundled distroprefs to the user's prefs directory.
# CPAN reads prefs from the filesystem, so we write bundled YAML files
# to ~/.perlonjava/cpan/prefs/ on first run.
# Canonical sources live under lib/PerlOnJava/CpanDistroprefs/ in the JAR
# (see dev/design/patch-and-cpan-prefs-layout.md).
# Note: ~/.perlonjava/cpan/CPAN/MyConfig.pm is created by HandleConfig.pm.
sub _bootstrap_prefs {
    my $prefs_dir = File::Spec->catdir($cpan_home, 'prefs');

    # dest filename under prefs_dir => source path relative to each @INC entry
    my %pref_install = (
        'Moo.yml'                    => 'PerlOnJava/CpanDistroprefs/Moo.yml',
        'Params-Validate.yml'        => 'PerlOnJava/CpanDistroprefs/Params-Validate.yml',
        'Moose.yml'                  => 'PerlOnJava/CpanDistroprefs/Moose.yml',
        'DBI.yml'                    => 'PerlOnJava/CpanDistroprefs/DBI.yml',
        'SQL-Translator.yml'         => 'PerlOnJava/CpanDistroprefs/SQL-Translator.yml',
        'XML-LibXML.yml'             => 'PerlOnJava/CpanDistroprefs/XML-LibXML.yml',
        'Net-Server.yml'             => 'PerlOnJava/CpanDistroprefs/Net-Server.yml',
        'CPAN-FindDependencies.yml'  => 'PerlOnJava/CpanDistroprefs/CPAN-FindDependencies.yml',
        'IO-Async.yml'               => 'PerlOnJava/CpanDistroprefs/IO-Async.yml',
        'Image-BMP.yml'              => 'PerlOnJava/CpanDistroprefs/Image-BMP.yml',
        'ExtUtils-CBuilder.yml'      => 'PerlOnJava/CpanDistroprefs/ExtUtils-CBuilder.yml',
        'ExtUtils-ParseXS.yml'       => 'PerlOnJava/CpanDistroprefs/ExtUtils-ParseXS.yml',
        'Module-Build.yml'           => 'PerlOnJava/CpanDistroprefs/Module-Build.yml',
        'Class-Method-Modifiers.yml' => 'PerlOnJava/CpanDistroprefs/Class-Method-Modifiers.yml',
        'Sub-Quote.yml'              => 'PerlOnJava/CpanDistroprefs/Sub-Quote.yml',
        'IPC-Run3.yml'               => 'PerlOnJava/CpanDistroprefs/IPC-Run3.yml',
        'Exception-Class.yml'        => 'PerlOnJava/CpanDistroprefs/Exception-Class.yml',
        'Module-Pluggable.yml'       => 'PerlOnJava/CpanDistroprefs/Module-Pluggable.yml',
        'Path-Tiny.yml'              => 'PerlOnJava/CpanDistroprefs/Path-Tiny.yml',
        'Test2-Plugin-NoWarnings.yml' => 'PerlOnJava/CpanDistroprefs/Test2-Plugin-NoWarnings.yml',
        'Params-ValidationCompiler.yml' => 'PerlOnJava/CpanDistroprefs/Params-ValidationCompiler.yml',
        'Test-Deep.yml'              => 'PerlOnJava/CpanDistroprefs/Test-Deep.yml',
        'Test-Deep-JSON.yml'         => 'PerlOnJava/CpanDistroprefs/Test-Deep-JSON.yml',
        'Test-Warnings.yml'          => 'PerlOnJava/CpanDistroprefs/Test-Warnings.yml',
        'File-Copy-Recursive.yml'    => 'PerlOnJava/CpanDistroprefs/File-Copy-Recursive.yml',
        'Test-File-ShareDir.yml'     => 'PerlOnJava/CpanDistroprefs/Test-File-ShareDir.yml',
        'DateTime-Locale.yml'        => 'PerlOnJava/CpanDistroprefs/DateTime-Locale.yml',
        'Test-File.yml'              => 'PerlOnJava/CpanDistroprefs/Test-File.yml',
        'Data-Dmp.yml'               => 'PerlOnJava/CpanDistroprefs/Data-Dmp.yml',
    );
    $pref_install{'OpenAI-API.yml'} = $ENV{PERLONJAVA_OPENAI_LIVE_TESTING}
        ? 'PerlOnJava/CpanDistroprefs/OpenAI-API.live.yml'
        : 'PerlOnJava/CpanDistroprefs/OpenAI-API.offline.yml';

    my $slurp = sub {
        my ($path) = @_;
        open my $fh, '<', $path or return undef;
        my $content = do { local $/; <$fh> };
        close $fh;
        return $content;
    };

    my $find_source = sub {
        my ($src_rel) = @_;
        return undef unless defined $src_rel;
        for my $inc (@INC) {
            my $candidate = File::Spec->catfile($inc, $src_rel);
            return $candidate if -f $candidate;
        }
        return undef;
    };

    # Create prefs directory if needed
    unless (-d $prefs_dir) {
        require File::Path;
        File::Path::make_path($prefs_dir);
    }

    for my $file (sort keys %pref_install) {
        my $src_rel = $pref_install{$file};
        my $src_path = $find_source->($src_rel);
        next unless defined $src_path;
        my $bundled = $slurp->($src_path);
        next unless defined $bundled;

        my $dest = File::Spec->catfile($prefs_dir, $file);
        if (-f $dest) {
            # Only overwrite if the existing file was written by PerlOnJava
            # (contains our signature).  A file without the signature is a
            # genuine user customization and must not be touched.
            open my $rfh, '<', $dest or next;
            my $existing = do { local $/; <$rfh> };
            close $rfh;
            next unless $existing =~ /PerlOnJava/;
            # Skip if content is already up to date (avoid needless writes).
            next if $existing eq $bundled;
        }
        if (open my $fh, '>', $dest) {
            print $fh $bundled;
            close $fh;
        }
    }
}
_bootstrap_prefs();

# Bootstrap CPAN patches (referenced by distroprefs' `patches:` key).
#
# CPAN::Distribution applies these via /usr/bin/patch before make/test/
# install runs. We ship the patch sources bundled in the JAR under
# lib/PerlOnJava/CpanPatches/ and copy them out to
# ~/.perlonjava/cpan/patches/ on first run so the external `patch`
# binary (which operates on the filesystem) can reach them.
#
# Patches are keyed by "<Distribution>-<version>/<filename>.patch"
# relative to $CPAN::Config->{patches_dir}.
# Source files live under lib/PerlOnJava/CpanPatches/ (see
# dev/design/patch-and-cpan-prefs-layout.md).
sub _bootstrap_patches {
    my $patches_dir = File::Spec->catdir($cpan_home, 'patches');

    # Map: target path relative to $patches_dir  =>  source path inside the JAR
    # (or on-disk dev tree during `make`). The source is located via @INC.
    my @bundled = (
        [ 'DBI-1.647/DBI.pm.patch',
          'PerlOnJava/CpanPatches/DBI-1.647/DBI.pm.patch' ],
        [ 'DBI-1.647/PurePerl.pm.patch',
          'PerlOnJava/CpanPatches/DBI-1.647/PurePerl.pm.patch' ],
        [ 'Net-Server-2.018/Proto.pm.patch',
          'PerlOnJava/CpanPatches/Net-Server-2.018/Proto.pm.patch' ],
        [ 'CPAN-FindDependencies-3.13/MakeMaker.pm.patch',
          'PerlOnJava/CpanPatches/CPAN-FindDependencies-3.13/MakeMaker.pm.patch' ],
        [ 'IO-Async-0.805/NoFork.patch',
          'PerlOnJava/CpanPatches/IO-Async-0.805/NoFork.patch' ],
        [ 'IO-Async-0.805/PerlOnJava.patch',
          'PerlOnJava/CpanPatches/IO-Async-0.805/PerlOnJava.patch' ],
        [ 'OpenAI-API-0.37/EventLoop.patch',
          'PerlOnJava/CpanPatches/OpenAI-API-0.37/EventLoop.patch' ],
        [ 'OpenAI-API-0.37/NoNetworkTests.patch',
          'PerlOnJava/CpanPatches/OpenAI-API-0.37/NoNetworkTests.patch' ],
        [ 'Image-BMP-1.26/BMP.pm.patch',
          'PerlOnJava/CpanPatches/Image-BMP-1.26/BMP.pm.patch' ],
        [ 'Data-Dmp-0.242/PerlOnJava.patch',
          'PerlOnJava/CpanPatches/Data-Dmp-0.242/PerlOnJava.patch' ],
    );

    my $slurp = sub {
        my ($path) = @_;
        open my $fh, '<', $path or return undef;
        my $content = do { local $/; <$fh> };
        close $fh;
        return $content;
    };

    my $find_source = sub {
        my ($src_rel) = @_;
        return undef unless defined $src_rel;
        for my $inc (@INC) {
            my $candidate = File::Spec->catfile($inc, $src_rel);
            return $candidate if -f $candidate;
        }
        return undef;
    };

    # Fast path: if every target exists and bundled targets are current, skip everything.
    my $needs_write = 0;
    for my $pair (@bundled) {
        my ($rel, $src_rel) = @$pair;
        my $dest = File::Spec->catfile($patches_dir, $rel);
        unless (-f $dest) { $needs_write = 1; last }

        my $src = $find_source->($src_rel);
        my $expected_content = defined $src ? $slurp->($src) : undef;
        next unless defined $expected_content;

        my $existing = $slurp->($dest);
        if (!defined($existing) || $existing ne $expected_content) {
            $needs_write = 1;
            last;
        }
    }
    return unless $needs_write;

    require File::Path;
    for my $pair (@bundled) {
        my ($rel, $src_rel) = @$pair;
        my $dest = File::Spec->catfile($patches_dir, $rel);
        my $dest_dir = File::Spec->catpath('', (File::Spec->splitpath($dest))[0,1]);
        File::Path::make_path($dest_dir) unless -d $dest_dir;

        # Locate the source file in @INC (finds either jar:PERL5LIB/… at
        # runtime or src/main/perl/lib/… during make/test).
        my $src = $find_source->($src_rel);
        my $content = defined $src ? $slurp->($src) : undef;
        next unless defined $content;

        my $existing = -f $dest ? $slurp->($dest) : undef;
        next if defined($existing) && $existing eq $content;

        if (open my $out, '>', $dest) {
            print $out $content;
            close $out;
        }
    }
}
_bootstrap_patches();

$CPAN::Config = {
    'applypatch' => q[],
    'auto_commit' => q[0],
    'build_cache' => q[100],
    'build_dir' => File::Spec->catdir($cpan_home, 'build'),
    'build_dir_reuse' => q[0],
    'build_requires_install_policy' => q[yes],
    'bzip2' => $is_windows ? q[] : q[/usr/bin/bzip2],
    'cache_metadata' => q[1],
    'check_sigs' => q[0],
    'cleanup_after_install' => q[0],
    'colorize_output' => q[0],
    'commandnumber_in_prompt' => q[1],
    'connect_to_internet_ok' => q[1],
    'cpan_home' => $cpan_home,
    'curl' => $is_windows ? q[] : q[/usr/bin/curl],
    'ftp_passive' => q[1],
    'ftp_proxy' => q[],
    'getcwd' => q[cwd],
    'gzip' => $is_windows ? q[] : q[/usr/bin/gzip],
    'halt_on_failure' => q[0],
    'histfile' => File::Spec->catfile($cpan_home, 'histfile'),
    'histsize' => q[100],
    'http_proxy' => q[],
    'inactivity_timeout' => q[0],
    'index_expire' => q[1],
    'inhibit_startup_message' => q[1],  # Don't ask for config on first run
    'keep_source_where' => File::Spec->catdir($cpan_home, 'sources'),
    'load_module_verbosity' => q[none],
    'make' => $is_windows ? q[dmake] : q[/usr/bin/make],
    'make_arg' => q[],
    'make_install_arg' => q[],
    'make_install_make_command' => $is_windows ? q[dmake] : q[/usr/bin/make],
    'makepl_arg' => q[],
    'mbuild_arg' => q[],
    'mbuild_install_arg' => q[],
    'mbuild_install_build_command' => $is_windows ? q[Build] : q[./Build],
    'mbuildpl_arg' => q[],
    'no_proxy' => q[],
    'pager' => $is_windows ? q[more] : q[/usr/bin/less],
    'patch' => $is_windows ? q[] : q[/usr/bin/patch],
    'patches_dir' => File::Spec->catdir($cpan_home, 'patches'),
    'perl5lib_verbosity' => q[none],
    'prefer_external_tar' => q[1],
    'prefer_installer' => q[MB],
    'prefs_dir' => File::Spec->catdir($cpan_home, 'prefs'),
    'prerequisites_policy' => q[follow],
    'recommends_policy' => q[1],
    'scan_cache' => q[atstart],
    'shell' => $is_windows ? $ENV{COMSPEC} || 'cmd.exe' : '/bin/bash',
    'show_unparsable_versions' => q[0],
    'show_upload_date' => q[0],
    'show_zero_hierarchies' => q[0],
    'suggests_policy' => q[0],
    'tar' => $is_windows ? q[] : q[/usr/bin/tar],
    'tar_verbosity' => q[none],
    'term_is_latin' => q[1],
    'term_ornaments' => q[1],
    'test_report' => q[0],
    'trust_test_report_history' => q[0],
    'unzip' => $is_windows ? q[] : q[/usr/bin/unzip],
    'urllist' => [q[https://cpan.metacpan.org/]],
    'use_prompt_default' => q[1],  # Auto-accept defaults
    'use_sqlite' => q[0],
    'version_timeout' => q[15],
    'wget' => q[],
    'yaml_load_code' => q[0],
    'yaml_module' => q[YAML],
    'pushy_https' => q[1],  # Use new HTTPS-only download mechanism
};

1;

__END__

=head1 NAME

CPAN::Config - Default CPAN configuration for PerlOnJava

=head1 DESCRIPTION

This module provides default CPAN configuration for PerlOnJava.
It uses C<~/.perlonjava/cpan> as the CPAN home directory for consistency
with other PerlOnJava conventions.

Users can override these settings by creating their own config file at:

    ~/.perlonjava/cpan/CPAN/MyConfig.pm

=head1 SEE ALSO

L<CPAN>, L<CPAN::HandleConfig>

=cut
