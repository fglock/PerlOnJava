# CPAN Configuration for PerlOnJava
# This provides sensible defaults that work out of the box
# Users can override with $PERLONJAVA_HOME/cpan/CPAN/MyConfig.pm

package CPAN::Config;
use strict;
use warnings;
use File::Spec;

# Determine home directory cross-platform
my $home = $ENV{HOME} || $ENV{USERPROFILE} || '.';
my $perlonjava_home = defined($ENV{PERLONJAVA_HOME}) && length($ENV{PERLONJAVA_HOME})
    ? $ENV{PERLONJAVA_HOME}
    : File::Spec->catdir($home, '.perlonjava');

# Keep all CPAN data below the selected PerlOnJava home.
my $cpan_home = File::Spec->catdir($perlonjava_home, 'cpan');

# Determine OS-specific tools
my $is_windows = $^O eq 'MSWin32' || $^O eq 'cygwin';

# Bootstrap bundled distroprefs to the user's prefs directory.
# CPAN reads prefs from the filesystem, so we write bundled YAML files
# to $PERLONJAVA_HOME/cpan/prefs/ (or the default equivalent) on first run.
# Canonical sources live under lib/PerlOnJava/CpanDistroprefs/ in the JAR
# (see dev/design/patch-and-cpan-prefs-layout.md).
# Note: $PERLONJAVA_HOME/cpan/CPAN/MyConfig.pm is created by HandleConfig.pm.
sub _bootstrap_prefs {
    my $prefs_dir = File::Spec->catdir($cpan_home, 'prefs');

    # dest filename under prefs_dir => source path relative to each @INC entry
    my %pref_install = (
        'Moo.yml'                    => 'PerlOnJava/CpanDistroprefs/Moo.yml',
        'Params-Validate.yml'        => 'PerlOnJava/CpanDistroprefs/Params-Validate.yml',
        'AnyEvent.yml'               => 'PerlOnJava/CpanDistroprefs/AnyEvent.yml',
        'Net-Server.yml'             => 'PerlOnJava/CpanDistroprefs/Net-Server.yml',
        'Error-Pure.yml'             => 'PerlOnJava/CpanDistroprefs/Error-Pure.yml',
        'Error.yml'                  => 'PerlOnJava/CpanDistroprefs/Error.yml',
        'IO-All.yml'                 => 'PerlOnJava/CpanDistroprefs/IO-All.yml',
        'IO-Async.yml'               => 'PerlOnJava/CpanDistroprefs/IO-Async.yml',
        'IO-Socket-INET6.yml'        => 'PerlOnJava/CpanDistroprefs/IO-Socket-INET6.yml',
        'Image-BMP.yml'              => 'PerlOnJava/CpanDistroprefs/Image-BMP.yml',
        'Javascript-Menu-Full.yml'   => 'PerlOnJava/CpanDistroprefs/Javascript-Menu-Full.yml',
        'CGI-Widget-Tabs.yml'        => 'PerlOnJava/CpanDistroprefs/CGI-Widget-Tabs.yml',
        'Pod-Parser.yml'             => 'PerlOnJava/CpanDistroprefs/Pod-Parser.yml',
        'POD-Tested.yml'             => 'PerlOnJava/CpanDistroprefs/POD-Tested.yml',
        'ExtUtils-ParseXS.yml'       => 'PerlOnJava/CpanDistroprefs/ExtUtils-ParseXS.yml',
        'Class-Trait.yml'            => 'PerlOnJava/CpanDistroprefs/Class-Trait.yml',
        'Exception-Class.yml'        => 'PerlOnJava/CpanDistroprefs/Exception-Class.yml',
        'Module-Pluggable-Ordered.yml' => 'PerlOnJava/CpanDistroprefs/Module-Pluggable-Ordered.yml',
        'Logger-Simple.yml'        => 'PerlOnJava/CpanDistroprefs/Logger-Simple.yml',
        'Test-Block.yml'             => 'PerlOnJava/CpanDistroprefs/Test-Block.yml',
        'Test-Deep-JSON.yml'         => 'PerlOnJava/CpanDistroprefs/Test-Deep-JSON.yml',
        'Test-SharedFork.yml'        => 'PerlOnJava/CpanDistroprefs/Test-SharedFork.yml',
        'Test-TCP.yml'               => 'PerlOnJava/CpanDistroprefs/Test-TCP.yml',
        'Test-Trap.yml'              => 'PerlOnJava/CpanDistroprefs/Test-Trap.yml',
        'Filesys-Notify-Simple.yml'  => 'PerlOnJava/CpanDistroprefs/Filesys-Notify-Simple.yml',
        'Class-C3-Adopt-NEXT.yml'    => 'PerlOnJava/CpanDistroprefs/Class-C3-Adopt-NEXT.yml',
        'Catalyst-Runtime.yml'       => 'PerlOnJava/CpanDistroprefs/Catalyst-Runtime.yml',
        'MooseX-Types-Path-Tiny.yml' => 'PerlOnJava/CpanDistroprefs/MooseX-Types-Path-Tiny.yml',
        'Text-SimpleTable.yml'       => 'PerlOnJava/CpanDistroprefs/Text-SimpleTable.yml',
        'Data-Dmp.yml'               => 'PerlOnJava/CpanDistroprefs/Data-Dmp.yml',
        'Capture-Tiny.yml'           => 'PerlOnJava/CpanDistroprefs/Capture-Tiny.yml',
        'String-ShellQuote.yml'      => 'PerlOnJava/CpanDistroprefs/String-ShellQuote.yml',
        'Template.yml'               => 'PerlOnJava/CpanDistroprefs/Template.yml',
        'Test-Differences.yml'       => 'PerlOnJava/CpanDistroprefs/Test-Differences.yml',
        'Parse-RecDescent.yml'       => 'PerlOnJava/CpanDistroprefs/Parse-RecDescent.yml',
        'Crypt-URandom.yml'          => 'PerlOnJava/CpanDistroprefs/Crypt-URandom.yml',
        'Type-Tiny.yml'              => 'PerlOnJava/CpanDistroprefs/Type-Tiny.yml',
        'Class-DBI.yml'              => 'PerlOnJava/CpanDistroprefs/Class-DBI.yml',
        'XML-Filter-GenericChunk.yml' => 'PerlOnJava/CpanDistroprefs/XML-Filter-GenericChunk.yml',
        'XML-TreePP.yml'             => 'PerlOnJava/CpanDistroprefs/XML-TreePP.yml',
        'Net-Async-WebSocket.yml'    => 'PerlOnJava/CpanDistroprefs/Net-Async-WebSocket.yml',
        'Future-AsyncAwait.yml'      => 'PerlOnJava/CpanDistroprefs/Future-AsyncAwait.yml',
        'LWP-Protocol-https.yml'     => 'PerlOnJava/CpanDistroprefs/LWP-Protocol-https.yml',
        'PerlIO-via-Timeout.yml'     => 'PerlOnJava/CpanDistroprefs/PerlIO-via-Timeout.yml',
        'Device-SerialPort.yml'      => 'PerlOnJava/CpanDistroprefs/Device-SerialPort.yml',
        'XML-FromPerl.yml'           => 'PerlOnJava/CpanDistroprefs/XML-FromPerl.yml',
        'LRU-Cache.yml'               => 'PerlOnJava/CpanDistroprefs/LRU-Cache.yml',
        'Sort-External.yml'            => 'PerlOnJava/CpanDistroprefs/Sort-External.yml',
        'Char-Latin7.yml'               => 'PerlOnJava/CpanDistroprefs/Char-Latin7.yml',
        'Text-Markdown.yml'             => 'PerlOnJava/CpanDistroprefs/Text-Markdown.yml',
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

    # Bundled prefs are copied outside the JAR and survive upgrades. Retire
    # entries whose compatibility workaround moved into the runtime, but only
    # when the on-disk file still carries PerlOnJava's signature so user-owned
    # CPAN preferences are never removed.
    for my $file (qw(
        Test-FailWarnings.yml
        DateTime-Format-CLDR.yml
        Test-Class.yml
        Test-Warnings.yml
        File-Copy-Recursive.yml
        Test-File.yml
        WWW-Form-UrlEncoded.yml
        DBI.yml
        Moose.yml
        CryptX.yml
        HTML-Parser.yml
        XML-LibXML.yml
        Set-Object.yml
        Package-Stash-XS.yml
        Acrux.yml
        CGI-Simple.yml
        CGI.yml
        Crypt-OpenSSL-RSA.yml
        DB-File.yml
        File-Slurp.yml
        HTTP-Server-Simple.yml
        Hook-LexWrap.yml
        Module-Patch.yml
        Mojolicious.yml
        Monkey-Patch-Action.yml
        REST-Client.yml
        String-Random.yml
        Sub-Delete.yml
        Test-MockObject.yml
        UNIVERSAL-can.yml
        UNIVERSAL-isa.yml
        WWW-Suffit-UserAgent.yml
        WWW-Suffit.yml
        libwww-perl.yml
        HTTP-Daemon.yml
        HTTP-Message.yml
        IO-Compress.yml
        IO-HTML.yml
        Module-Build.yml
        Object-Event.yml
        Object-InsideOut.yml
        String-Print.yml
        WWW-RobotRules.yml
        XML-Filter-GenericChunk.yml
        HTTP-Response-Encoding.yml
        CPAN-FindDependencies.yml
        SQL-Translator.yml
        Aliased.yml
        Carp-Assert.yml
        Class-Method-Modifiers.yml
        DateTime-Locale.yml
        Devel-Symdump.yml
        ExtUtils-CBuilder.yml
        IPC-Run.yml
        IPC-Run3.yml
        Module-Install.yml
        Module-Pluggable.yml
        Params-ValidationCompiler.yml
        Path-Tiny.yml
        Readonly.yml
        Regexp-Common.yml
        Sub-Quote.yml
        Test-Deep.yml
        Test-Deep-JSON.yml
        Test-File-ShareDir.yml
        Test2-Plugin-NoWarnings.yml
        Term-ANSIColor-Markup.yml
        Graph.yml
    )) {
        my $dest = File::Spec->catfile($prefs_dir, $file);
        next unless -f $dest;
        my $existing = $slurp->($dest);
        unlink $dest if defined($existing) && $existing =~ /PerlOnJava/;
    }

    # Logger-Simple.yml was historically shipped without the PerlOnJava
    # ownership signature used above. Upgrade only that exact legacy policy;
    # otherwise the cross-platform test.env replacement can never reach homes
    # which bootstrapped the old shell-assignment commandline.
    my $legacy_logger = File::Spec->catfile($prefs_dir, 'Logger-Simple.yml');
    if (-f $legacy_logger) {
        my $existing = $slurp->($legacy_logger);
        if (defined($existing)
            && $existing =~ /distribution:\s*["']?\^TSTANLEY\/Logger-Simple-/
            && $existing =~ /commandline:\s*["']JPERL_UNIMPLEMENTED=warn make test["']/) {
            unlink $legacy_logger;
        }
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
# $PERLONJAVA_HOME/cpan/patches/ on first run so the external `patch`
# binary (which operates on the filesystem) can reach them.
#
# Patches are exposed under "<Distribution>/<filename>.patch" relative to
# $CPAN::Config->{patches_dir}.  The source paths retain the upstream version
# they were authored against for provenance, but the installed path is stable
# across CPAN releases.  CPAN will still reject a patch whose context no
# longer applies, which is safer than silently applying an unrelated patch.
# Source files live under lib/PerlOnJava/CpanPatches/ (see
# dev/design/patch-and-cpan-prefs-layout.md).
sub _bootstrap_patches {
    my $patches_dir = File::Spec->catdir($cpan_home, 'patches');

    # Map: target path relative to $patches_dir  =>  source path inside the JAR
    # (or on-disk dev tree during `make`). The source is located via @INC.
    my @bundled = (
        [ 'Exception-Class/GeneratedSubclassVersion.patch',
          'PerlOnJava/CpanPatches/Exception-Class-1.45/GeneratedSubclassVersion.patch' ],
        [ 'Net-Server/SkipForkTests.patch',
          'PerlOnJava/CpanPatches/Net-Server-2.018/SkipForkTests.patch' ],
        [ 'Device-SerialPort/NoXsBitsFallback.patch',
          'PerlOnJava/CpanPatches/Device-SerialPort-1.04/NoXsBitsFallback.patch' ],
        [ 'Pod-Parser/Pod-Find-core-probe.patch',
          'PerlOnJava/CpanPatches/Pod-Parser-1.67/Pod-Find-core-probe.patch' ],
        [ 'IO-Async/NoFork.patch',
          'PerlOnJava/CpanPatches/IO-Async-0.805/NoFork.patch' ],
        [ 'IO-Async/PerlOnJava.patch',
          'PerlOnJava/CpanPatches/IO-Async-0.805/PerlOnJava.patch' ],
        [ 'IO-Async/SkipUnsupportedSocketTests.patch',
          'PerlOnJava/CpanPatches/IO-Async-0.805/SkipUnsupportedSocketTests.patch' ],
        [ 'Net-Async-WebSocket/PlainPerlAccessors.patch',
          'PerlOnJava/CpanPatches/Net-Async-WebSocket-0.14/PlainPerlAccessors.patch' ],
        [ 'Future-AsyncAwait/RemoveNativePrereqs.patch',
          'PerlOnJava/CpanPatches/Future-AsyncAwait-0.71/RemoveNativePrereqs.patch' ],
        [ 'IO-Socket-INET6/SkipForkSocketTests.patch',
          'PerlOnJava/CpanPatches/IO-Socket-INET6-2.73/SkipForkSocketTests.patch' ],
        [ 'OpenAI-API/EventLoop.patch',
          'PerlOnJava/CpanPatches/OpenAI-API-0.37/EventLoop.patch' ],
        [ 'OpenAI-API/NoNetworkTests.patch',
          'PerlOnJava/CpanPatches/OpenAI-API-0.37/NoNetworkTests.patch' ],
        [ 'Image-BMP/BMP.pm.patch',
          'PerlOnJava/CpanPatches/Image-BMP-1.26/BMP.pm.patch' ],
        [ 'Javascript-Menu-Full/NoCGIDependency.patch',
          'PerlOnJava/CpanPatches/Javascript-Menu-Full-2.02/NoCGIDependency.patch' ],
        [ 'CGI-Widget-Tabs/OptionalAuthorAndCGITests.patch',
          'PerlOnJava/CpanPatches/CGI-Widget-Tabs-1.14/OptionalAuthorAndCGITests.patch' ],
        [ 'Data-Dmp/PerlOnJava.patch',
          'PerlOnJava/CpanPatches/Data-Dmp-0.242/PerlOnJava.patch' ],
        [ 'Capture-Tiny/NoForkTeeCatchErrors.patch',
          'PerlOnJava/CpanPatches/Capture-Tiny-0.50/NoForkTeeCatchErrors.patch' ],
        [ 'Error/SkipForkWarndie.patch',
          'PerlOnJava/CpanPatches/Error-0.17030/SkipForkWarndie.patch' ],
        [ 'Error-Pure/PlainLexicalConstants.patch',
          'PerlOnJava/CpanPatches/Error-Pure-0.34/PlainLexicalConstants.patch' ],
        [ 'String-ShellQuote/SkipForkScriptTests.patch',
          'PerlOnJava/CpanPatches/String-ShellQuote-1.04/SkipForkScriptTests.patch' ],
        [ 'IO-All/SkipForkTests.patch',
          'PerlOnJava/CpanPatches/IO-All-0.87/SkipForkTests.patch' ],
        [ 'Module-Pluggable-Ordered/LimitFixturePlugins.patch',
          'PerlOnJava/CpanPatches/Module-Pluggable-Ordered-1.5/LimitFixturePlugins.patch' ],
        [ 'LWP-Protocol-https/SkipForkProxyTest.patch',
          'PerlOnJava/CpanPatches/LWP-Protocol-https-6.15/SkipForkProxyTest.patch' ],
        [ 'Type-Tiny/SkipRegexCallbackTests.patch',
          'PerlOnJava/CpanPatches/Type-Tiny-2.010001/SkipRegexCallbackTests.patch' ],
        [ 'PerlIO-via-Timeout/SkipViaRuntimeTest.patch',
          'PerlOnJava/CpanPatches/PerlIO-via-Timeout-0.32/SkipViaRuntimeTest.patch' ],
        [ 'Crypt-URandom/PerlOnJavaTests.patch',
          'PerlOnJava/CpanPatches/Crypt-URandom-0.55/PerlOnJavaTests.patch' ],
        [ 'Test-Trap/ExitTrap.patch',
          'PerlOnJava/CpanPatches/Test-Trap/ExitTrap.patch' ],
        [ 'Test-Trap/TempFileGlobLocalization.patch',
          'PerlOnJava/CpanPatches/Test-Trap/TempFileGlobLocalization.patch' ],
        [ 'Parse-RecDescent/SkipStandalonePrecompile.patch',
          'PerlOnJava/CpanPatches/Parse-RecDescent-1.967015/SkipStandalonePrecompile.patch' ],
        [ 'Parse-RecDescent/SkipReproducibleStandalone.patch',
          'PerlOnJava/CpanPatches/Parse-RecDescent-1.967015/SkipReproducibleStandalone.patch' ],
        [ 'XML-FromPerl/Makefile.PL.patch',
          'PerlOnJava/CpanPatches/XML-FromPerl-0.01/Makefile.PL.patch' ],
        [ 'Class-DBI/Class-DBI.pm.patch',
          'PerlOnJava/CpanPatches/Class-DBI-v3.0.17/Class-DBI.pm.patch' ],
        [ 'Class-Trait/SkipObsoleteModPerlWarningTest.patch',
          'PerlOnJava/CpanPatches/Class-Trait-0.33/SkipObsoleteModPerlWarningTest.patch' ],
        [ 'XML-TreePP/TreePP.pm.patch',
          'PerlOnJava/CpanPatches/XML-TreePP-0.43/TreePP.pm.patch' ],
        [ 'LRU-Cache/PurePerl.patch',
          'PerlOnJava/CpanPatches/LRU-Cache-1.00/PurePerl.patch' ],
        [ 'Sort-External/PurePerl.patch',
          'PerlOnJava/CpanPatches/Sort-External-0.18/PurePerl.patch' ],
        [ 'Char-Latin7/PerlOnJavaExecutable.patch',
          'PerlOnJava/CpanPatches/Char-Latin7-1.15/PerlOnJavaExecutable.patch' ],
        [ 'Text-Markdown/BoundedBalancedPatterns.patch',
          'PerlOnJava/CpanPatches/Text-Markdown-1.000031/BoundedBalancedPatterns.patch' ],
    );

    # Like prefs, extracted patch files persist after an upgrade. These paths
    # are owned by PerlOnJava and no current distropref references them.
    for my $rel (
        'Test-FailWarnings-0.008/CallerOrigin.patch',
        'DateTime-Format-CLDR-1.19/ByteSafePatternLiterals.patch',
        'Net-Server/Proto.pm.patch',
        'WWW-Form-UrlEncoded/PP.pm.patch',
        'DBI/DBI.pm.patch',
        'DBI/PurePerl.pm.patch',
        'Graph/Graph.pm.patch',
        'Graph/AdjacencyMap-Light.pm.patch',
        'Graph/AdjacencyMap.pm.patch',
        'Module-Install/ExplicitAuthorsMethod.patch',
        'Term-ANSIColor-Markup/PortableAccessors.patch',
        'HTTP-Response-Encoding/Makefile.PL.patch',
        'CPAN-FindDependencies/MakeMaker.pm.patch',
    ) {
        my $retired = File::Spec->catfile($patches_dir, $rel);
        unlink $retired if -f $retired;
    }

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
    # Native accelerator recommendations frequently have no JVM value and can
    # pull large XS-only dependency trees into otherwise pure-Perl installs.
    # Required dependencies are still followed normally; users may opt back in.
    'recommends_policy' => q[0],
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
It uses C<$PERLONJAVA_HOME/cpan> as the CPAN home directory when the
C<PERLONJAVA_HOME> environment variable is set. Otherwise it defaults to
C<~/.perlonjava/cpan>.

Users can override these settings by creating their own config file at:

    $PERLONJAVA_HOME/cpan/CPAN/MyConfig.pm

=head1 SEE ALSO

L<CPAN>, L<CPAN::HandleConfig>

=cut
