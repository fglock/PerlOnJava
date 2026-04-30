package ExtUtils::MakeMaker;
use strict;
use warnings;

our $VERSION = '7.78';
our $Verbose = 0;

use Exporter 'import';
our @EXPORT = qw(WriteMakefile prompt);
our @EXPORT_OK = qw($VERSION $Verbose neatvalue _sprintf562);

use File::Copy;
use File::Path qw(make_path);
use File::Find;
use File::Spec;
use File::Basename;
use Cwd qw(getcwd abs_path);
use Config;

# Load ExtUtils::MM to set up the MM package with parse_version, etc.
# CPAN.pm and other tools expect MM->parse_version() to work after loading MakeMaker
require ExtUtils::MM;

# Installation directory - priority: environment > Config.pm > fallback
our $INSTALL_BASE = $ENV{PERLONJAVA_LIB} || $Config{installsitelib} || _default_install_base();

# Parse command-line arguments like INSTALL_BASE=/path
# This is called by Makefile.PL scripts that use MY->parse_args(@ARGV)
sub parse_args {
    my ($self, @args) = @_;
    $self = {} unless ref $self;
    foreach (@args) {
        next unless m/(.*?)=(.*)/;
        my ($name, $value) = ($1, $2);
        # Expand ~ in paths
        if ($value =~ m/^~/) {
            my $home = $ENV{HOME} || $ENV{USERPROFILE} || '.';
            $value =~ s/^~/$home/;
        }
        $self->{ARGS}{uc $name} = $self->{uc $name} = $value;
    }
    return $self;
}

# Find the default lib directory
sub _default_install_base {
    # Check if running from JAR
    if ($ENV{PERLONJAVA_JAR}) {
        my $jar_dir = dirname($ENV{PERLONJAVA_JAR});
        return File::Spec->catdir($jar_dir, 'lib');
    }
    # Use ~/.perlonjava/lib as default user library path
    my $home = $ENV{HOME} || $ENV{USERPROFILE} || '.';
    return File::Spec->catdir($home, '.perlonjava', 'lib');
}

sub WriteMakefile {
    my %args = @_;
    
    my $name = $args{NAME} or die "NAME is required\n";
    my $version = $args{VERSION} || ($args{VERSION_FROM} && _extract_version($args{VERSION_FROM})) || '0';
    
    print "PerlOnJava MakeMaker: $name v$version\n";
    print "=" x 60, "\n";
    
    # Check prerequisites. Mirrors upstream ExtUtils::MakeMaker (5.42 / 7.76):
    # use a static filesystem search + parse_version (no `require`), warn per
    # missing prereq via STDERR, and only die when PREREQ_FATAL is set.
    # See ExtUtils::MakeMaker::_installed_file_for_module + the prereq loop in
    # WriteMakefile (lines ~579-647 of system MakeMaker).
    if ($args{PREREQ_PM} || $args{BUILD_REQUIRES} || $args{CONFIGURE_REQUIRES} || $args{TEST_REQUIRES}) {
        my %prereqs;
        for my $key (qw(PREREQ_PM BUILD_REQUIRES CONFIGURE_REQUIRES TEST_REQUIRES)) {
            next unless my $h = $args{$key};
            $prereqs{$_} = $h->{$_} for keys %$h;
        }
        my %unsatisfied = _check_prereqs(\%prereqs);
        if (%unsatisfied && $args{PREREQ_FATAL}) {
            my $failed = join "\n", map {"    $_ $unsatisfied{$_}"}
                                    sort { lc $a cmp lc $b } keys %unsatisfied;
            die <<"END";
MakeMaker FATAL: prerequisites not found.
$failed

Please install these modules first and rerun 'perl Makefile.PL'.
END
        }
    }
    
    # Check for XS files
    my @xs_files = _find_xs_files(\%args);
    
    if (@xs_files) {
        return _handle_xs_module($name, \@xs_files, \%args);
    }
    
    # Pure Perl - proceed with installation
    return _install_pure_perl($name, $version, \%args);
}

sub _installed_file_for_module {
    my ($module) = @_;
    my $file = $module;
    $file =~ s{::}{/}g;
    $file .= '.pm';
    for my $dir (@INC) {
        next if ref $dir;  # skip code/array refs and blessed @INC hooks
        my $candidate = File::Spec->catfile($dir, $file);
        return $candidate if -r $candidate;
    }
    return;
}

sub _check_prereqs {
    my ($prereqs) = @_;
    my %unsatisfied;

    for my $module (sort keys %$prereqs) {
        my $required = $prereqs->{$module};
        # 'perl' is a special key meaning minimum perl version, not a module
        next if $module eq 'perl';

        my $installed_file = _installed_file_for_module($module);
        if (!$installed_file) {
            warn "Warning: prerequisite $module $required not found.\n";
            $unsatisfied{$module} = 'not installed';
            next;
        }
        next unless $required;

        my $have = eval { MM->parse_version($installed_file) };
        $have = 0 if !defined $have || $have eq 'undef' || $@;
        if (_version_compare($have, $required) < 0) {
            warn "Warning: prerequisite $module $required not found. We have $have.\n";
            $unsatisfied{$module} = $required || 'unknown version';
        }
    }

    return %unsatisfied;
}

sub _version_compare {
    my ($v1, $v2) = @_;
    # Simple numeric comparison - handles most cases
    $v1 =~ s/_//g;
    $v2 =~ s/_//g;
    return ($v1 <=> $v2);
}

sub _find_xs_files {
    my ($args) = @_;
    my @xs;
    
    # Explicit XS hash
    if ($args->{XS}) {
        push @xs, keys %{$args->{XS}};
    }
    
    # C files that indicate XS
    if ($args->{C}) {
        push @xs, @{$args->{C}};
    }
    
    # Scan for .xs and .c files
    my $cwd = getcwd();
    find({
        wanted => sub {
            return unless -f;
            push @xs, $File::Find::name if /\.xs$/ || /\.c$/;
        },
        no_chdir => 1,
    }, $cwd);
    
    return @xs;
}

sub _handle_xs_module {
    my ($name, $xs_files, $args) = @_;
    
    # PerlOnJava cannot compile XS/C code, but we install .pm files anyway.
    # At runtime:
    #   - If PerlOnJava has a Java XS implementation, it will be used (fast path)
    #   - If not, XSLoader returns "loadable object" error
    #   - Modules with built-in PP fallback (like DateTime) will use it automatically
    #   - Modules without fallback will fail at runtime
    
    print "\n";
    print "XS MODULE: $name\n";
    print "=" x 60, "\n";
    print "\n";
    print "This module contains XS/C code. PerlOnJava cannot compile native code.\n\n";
    
    print "XS/C files found (will not be compiled):\n";
    for my $xs (sort @$xs_files) {
        print "  - $xs\n";
    }
    print "\n";
    
    print "Installing .pm files anyway. At runtime:\n";
    print "  - If PerlOnJava has a Java implementation, it will be used\n";
    print "  - Otherwise, the module's pure Perl fallback will be used (if available)\n";
    print "  - If no fallback exists, the module will fail to load\n";
    print "\n";
    
    # For XS modules, exclude .pm files that already have a PerlOnJava shim
    # in the JAR (jar:PERL5LIB).  The JAR shim provides proper fallback logic
    # (e.g. inheriting from a pure-Perl parent), while the CPAN version would
    # call XSLoader::load at the top level and die fatally.  Since
    # ~/.perlonjava/lib/ comes before jar:PERL5LIB in @INC, installing the
    # CPAN version would shadow the working shim.
    $args->{_xs_module} = 1;
    
    # Install the .pm files
    my $version = $args->{VERSION} || ($args->{VERSION_FROM} && _extract_version($args->{VERSION_FROM})) || '0';
    return _install_pure_perl($name, $version, $args);
}

sub _install_pure_perl {
    my ($name, $version, $args) = @_;
    
    my %pm;
    
    # Use explicit PM hash if provided
    if ($args->{PM}) {
        %pm = %{$args->{PM}};
        # Expand Make-style variables like $(INST_LIB) to actual paths.
        # Standard MakeMaker derives directory-bearing vars from NAME:
        #   INST_LIBDIR      = INST_LIB/<parent>    (e.g. blib/lib/Term)
        #   INST_ARCHLIBDIR  = INST_ARCHLIB/<parent>
        #   INST_AUTODIR     = INST_LIB/auto/<full>
        #   INST_ARCHAUTODIR = INST_ARCHLIB/auto/<full>
        # where <parent> is NAME with the last :: component removed and
        # <full> is the full NAME with :: replaced by /.
        my @parts = split /::/, ($name || '');
        pop @parts;  # drop BASEEXT (last component)
        my $parent_dir = @parts ? join('/', @parts) : '';
        (my $full_path = ($name || '')) =~ s{::}{/}g;
        my $libdir = $parent_dir
            ? File::Spec->catdir($INSTALL_BASE, $parent_dir)
            : $INSTALL_BASE;
        my $autodir = $full_path
            ? File::Spec->catdir($INSTALL_BASE, 'auto', $full_path)
            : $INSTALL_BASE;
        for my $key (keys %pm) {
            my $val = $pm{$key};
            # Directory-bearing variables (must come before the bare LIB/ARCHLIB forms)
            $val =~ s/\$\(INST_LIBDIR\)/$libdir/g;
            $val =~ s/\$\(INST_ARCHLIBDIR\)/$libdir/g;  # treat ARCHLIBDIR same as LIBDIR
            $val =~ s/\$\(INST_AUTODIR\)/$autodir/g;
            $val =~ s/\$\(INST_ARCHAUTODIR\)/$autodir/g;
            $val =~ s/\$\{INST_LIBDIR\}/$libdir/g;
            $val =~ s/\$\{INST_ARCHLIBDIR\}/$libdir/g;
            # Bare library roots
            $val =~ s/\$\(INST_LIB\)/$INSTALL_BASE/g;
            $val =~ s/\$\(INST_ARCHLIB\)/$INSTALL_BASE/g;  # treat ARCHLIB same as LIB
            $val =~ s/\$\{INST_LIB\}/$INSTALL_BASE/g;
            $val =~ s/\$\{INST_ARCHLIB\}/$INSTALL_BASE/g;
            $pm{$key} = $val;
        }
    } else {
        # Default: scan lib/ directory
        # Include .pm, .pl, and common data files (.dat, .json, .yml, .yaml, .xml, .txt, .pem)
        # Some modules like Image::ExifTool use .pl files loaded via require,
        # .dat files for data (e.g., Geolocation.dat), and Mozilla::CA uses .pem
        my $installable_re = qr/\.(?:pm|pl|pod|dat|json|ya?ml|xml|txt|cfg|conf|ini|pem)$/i;
        if (-d 'lib') {
            find({
                wanted => sub {
                    return unless -f && /$installable_re/;
                    my $src = $File::Find::name;
                    (my $rel = $src) =~ s{^lib/}{};
                    $pm{$src} = File::Spec->catfile($INSTALL_BASE, $rel);
                },
                no_chdir => 1,
            }, 'lib');
        }
        
        # Also check for blib/lib (after a build)
        if (-d 'blib/lib') {
            find({
                wanted => sub {
                    return unless -f && /$installable_re/;
                    my $src = $File::Find::name;
                    (my $rel = $src) =~ s{^blib/lib/}{};
                    $pm{$src} = File::Spec->catfile($INSTALL_BASE, $rel);
                },
                no_chdir => 1,
            }, 'blib/lib');
        }
        
        # Scan root-level .pm files and BASEEXT directory.
        # Standard MakeMaker maps:  ./*.pm => $(INST_LIBDIR)/*.pm
        # where INST_LIBDIR = INST_LIB/Parent/Path (derived from NAME).
        # PMLIBDIRS defaults to ['lib', $BASEEXT], so both lib/ (handled
        # above) and root .pm files / BASEEXT dir are always scanned.
        # This handles distributions like Math::Base::Convert where the
        # main .pm lives at the root alongside sub-modules in lib/.
        if ($name) {
            my @parts = split /::/, $name;
            my $baseext = pop @parts;  # BASEEXT (e.g. XML::Parser -> Parser)
            my $parent_dir = @parts ? File::Spec->catdir(@parts) : '';
            
            # Scan flat .pm files in current directory
            opendir(my $dh, '.') or warn "Cannot opendir .: $!";
            if ($dh) {
                while (my $file = readdir($dh)) {
                    next unless -f $file && $file =~ /\.pm$/i;
                    my $dest_rel = $parent_dir
                        ? File::Spec->catfile($parent_dir, $file)
                        : $file;
                    $pm{$file} = File::Spec->catfile($INSTALL_BASE, $dest_rel)
                        unless exists $pm{$file};
                }
                closedir($dh);
            }
            
            # Also scan BASEEXT directory recursively (standard MakeMaker PMLIBDIRS)
            # e.g. for XML::Parser, scan Parser/ which contains Style/*.pm
            if ($baseext && -d $baseext) {
                find({
                    wanted => sub {
                        return unless -f && /$installable_re/;
                        my $src = $File::Find::name;
                        my $rel = $parent_dir
                            ? File::Spec->catfile($parent_dir, $src)
                            : $src;
                        $pm{$src} = File::Spec->catfile($INSTALL_BASE, $rel)
                            unless exists $pm{$src};
                    },
                    no_chdir => 1,
                }, $baseext);
            }
        }
    }
    
    if (!%pm) {
        print "Warning: No installable files found (no .pm, .pl, .dat, etc.).\n";
        print "Expected structure: lib/Your/Module.pm\n\n";
        my $mm = PerlOnJava::MM::Installed->new($args);
        _create_mymeta($name, $version, $args);
        _create_install_makefile($name, $version, $args, {}, {}, $mm);
        _create_build_script_proxy() if -f 'Build.PL';
        return $mm;
    }
    
    # Skip .pm files that already exist in PerlOnJava's bundled JAR.
    # ~/.perlonjava/lib/ has higher @INC priority than jar:PERL5LIB, so
    # installing a CPAN version would shadow the bundled module.  This
    # protects Java-backed shims (IO::Socket::SSL, Net::SSLeay, etc.)
    # from being overwritten by incompatible CPAN versions, while still
    # allowing other files from the same distribution to be installed
    # (e.g. IO::Socket::SSL::Utils from the IO-Socket-SSL dist).
    #
    # Also track whether the *primary* module of this distribution was
    # the one we SKIPped: if so, the upstream tarball's t/*.t suite is
    # almost certainly testing a different architecture from our JAR
    # shim (e.g. CPAN DBD::JDBC drives an external Java proxy server,
    # whereas our bundled DBD::JDBC is an in-JVM driver). We use this
    # flag below to short-circuit `make test`.
    (my $primary_rel = $name) =~ s{::}{/}g;
    $primary_rel .= '.pm';
    my $primary_pm_bundled = 0;
    for my $src (sort keys %pm) {
        my $dest = $pm{$src};
        (my $rel = $dest) =~ s{^\Q$INSTALL_BASE\E/?}{};
        if ($rel && -f "jar:PERL5LIB/$rel") {
            print "  SKIP: $rel (bundled in PerlOnJava JAR)\n";
            $primary_pm_bundled = 1 if $rel eq $primary_rel;
            delete $pm{$src};
        }
    }
    $args->{_primary_pm_bundled} = $primary_pm_bundled;
    if ($primary_pm_bundled && !$ENV{JCPAN_RUN_BUNDLED_TESTS}) {
        print "  NOTE: $primary_rel is bundled; upstream test suite will be skipped.\n";
        print "        Set JCPAN_RUN_BUNDLED_TESTS=1 to run it anyway.\n";
    }
    
    print "\nWill install to: $INSTALL_BASE\n\n";
    
    # List files to be installed (deferred to 'make' for proper CPAN.pm dep resolution)
    for my $src (sort keys %pm) {
        print "  $src -> $pm{$src}\n";
    }
    
    # Collect share directory files (don't copy yet)
    my %share_files = _build_share_file_mapping($name, $args);
    if (%share_files) {
        print "\nShare files:\n";
        for my $src (sort keys %share_files) {
            print "  $src -> $share_files{$src}\n";
        }
        %pm = (%pm, %share_files);
    }
    
    # Collect script files
    my %scripts;
    if ($args->{EXE_FILES} && @{$args->{EXE_FILES}}) {
        my $bin_dir = File::Spec->catdir($INSTALL_BASE, '..', 'bin');
        for my $script (@{$args->{EXE_FILES}}) {
            $scripts{$script} = File::Spec->catfile($bin_dir, basename($script));
        }
        print "\nScripts:\n";
        for my $src (sort keys %scripts) {
            print "  $src -> $scripts{$src}\n";
        }
    }
    
    print "\n";
    
    # Create MM object first (needed by postamble)
    my $mm = PerlOnJava::MM::Installed->new($args);
    
    # Create MYMETA.yml for CPAN.pm dependency resolution
    _create_mymeta($name, $version, $args);
    
    # Create Makefile with install commands (actual install deferred to 'make')
    _create_install_makefile($name, $version, $args, \%pm, \%scripts, $mm);

    # If Build.PL exists in cwd, the distribution was configured via Module::Build
    # (or Module::Install's Build.PL-delegates-to-Makefile.PL trick). CPAN.pm will
    # then invoke `./Build`, `./Build test`, `./Build install` instead of `make`.
    # Write a thin Build script that delegates to the corresponding make targets,
    # so the Module::Build flow works on top of our Makefile.
    if (-f 'Build.PL') {
        _create_build_script_proxy();
    }
    
    my $total = scalar(keys %pm) + scalar(keys %scripts);
    print "=" x 60, "\n";
    print "Configured! $total files will be installed when 'make install' runs.\n";
    print "=" x 60, "\n\n";
    
    return $mm;
}

sub _build_share_file_mapping {
    my ($name, $args) = @_;
    my %files;
    
    # Check if File::ShareDir::Install was used
    return %files unless eval { @File::ShareDir::Install::DIRS };
    
    # Convert module name to dist name (My::Module -> My-Module)
    (my $dist_name = $name) =~ s/::/-/g;
    
    for my $def (@File::ShareDir::Install::DIRS) {
        my $type = $def->{type} || 'dist';
        next if $type =~ /^delete/;  # Skip delete directives
        
        # Get source directory - can be scalar or arrayref
        my @src_dirs;
        if (ref $def->{dir} eq 'ARRAY') {
            @src_dirs = @{$def->{dir}};
        } elsif ($def->{dir}) {
            @src_dirs = ($def->{dir});
        }
        
        # Scan files (don't copy yet - deferred to 'make')
        for my $src_dir (@src_dirs) {
            next unless -d $src_dir;
            
            my $dest_base;
            if ($type eq 'dist') {
                $dest_base = File::Spec->catdir($INSTALL_BASE, 'auto', 'share', 'dist', $dist_name);
            } elsif ($type eq 'module' && $def->{module}) {
                (my $mod_path = $def->{module}) =~ s/::/\//g;
                $dest_base = File::Spec->catdir($INSTALL_BASE, 'auto', 'share', 'module', $mod_path);
            } else {
                next;
            }
            
            find({
                wanted => sub {
                    return unless -f;
                    # Skip dotfiles unless requested
                    return if !$def->{dotfiles} && basename($_) =~ /^\./;
                    
                    my $src = $File::Find::name;
                    (my $rel = $src) =~ s{^\Q$src_dir\E/?}{};
                    $files{$src} = File::Spec->catfile($dest_base, $rel);
                },
                no_chdir => 1,
            }, $src_dir);
        }
    }
    
    return %files;
}

sub _extract_version {
    my ($file) = @_;
    return '0' unless -f $file;
    
    open my $fh, '<', $file or return '0';
    while (<$fh>) {
        if (/\$VERSION\s*=\s*['"]?([\d._]+)/) {
            return $1;
        }
        # Also handle: our $VERSION = version->declare('v1.2.3');
        if (/\$VERSION\s*=\s*version->/) {
            if (/['"]v?([\d.]+)/) {
                return $1;
            }
        }
    }
    close $fh;
    return '0';
}

sub _create_install_makefile {
    my ($name, $version, $args, $pm, $scripts, $mm) = @_;
    
    # Create a Makefile that builds into ./blib when 'make' runs, runs tests
    # against ./blib when 'make test' runs, and copies files to INSTALLSITELIB
    # only when 'make install' runs. This matches standard ExtUtils::MakeMaker
    # semantics so that 'cpan -t' (test-only) does not install the module,
    # and lets CPAN.pm resolve/install dependencies before the install step.
    # Respect custom MAKEFILE name if provided
    my $makefile = $args->{MAKEFILE} || 'Makefile';
    
    open my $fh, '>', $makefile or do {
        warn "Note: Could not create Makefile: $!\n";
        return;
    };
    
    # Get the Perl interpreter path
    my $perl = $^X;
    
    # Build test command - use TESTS from WriteMakefile args if provided, else default to t/*.t
    # Set PERL5LIB to include blib/lib and blib/arch so test subprocesses can find the module
    my $test_pattern = '';
    if (ref $args->{test} eq 'HASH' && $args->{test}{TESTS}) {
        $test_pattern = $args->{test}{TESTS};
    } elsif (-d 't') {
        $test_pattern = 't/*.t';
    }
    
    my $test_cmd;
    my $test_glob = ($args->{test} && $args->{test}{TESTS}) || '';
    $test_glob = 't/*.t' if !$test_glob && -d 't';
    if ($args->{_primary_pm_bundled} && !$ENV{JCPAN_RUN_BUNDLED_TESTS}) {
        # Primary .pm is JAR-bundled; the upstream tarball's tests almost
        # certainly target a different architecture (e.g. an external
        # proxy server) from our in-JVM shim, so running them will only
        # produce confusing failures. Emit a clear no-op test step.
        my $msg = "PerlOnJava: $name is bundled in the JAR; "
                . "skipping upstream test suite (incompatible architecture). "
                . "Set JCPAN_RUN_BUNDLED_TESTS=1 to run it anyway.";
        $msg =~ s/'/'\\''/g;
        $test_cmd = qq{\@$perl -e 'print "$msg\\n"'};
    } elsif ($test_glob) {
        # Use ExtUtils::Command::MM::test_harness with undef *Test::Harness::Switches
        # to disable the default -w switch, matching standard MakeMaker behavior
        $test_cmd = qq{PERL5LIB="./blib/lib:./blib/arch:\$\$PERL5LIB" $perl "-MExtUtils::Command::MM" "-MTest::Harness" "-e" "undef *Test::Harness::Switches; test_harness(0, './blib/lib', './blib/arch')" $test_glob};
    } elsif (-f 'test.pl') {
        # Legacy convention: some older CPAN dists use test.pl instead of t/*.t
        $test_cmd = qq{PERL5LIB="./blib/lib:./blib/arch:\$\$PERL5LIB" $perl test.pl};
    } else {
        $test_cmd = qq{$perl -e "print qq{PerlOnJava: No tests found (no t/ directory)\\n}"};
    }
    
    # Convert module name to dist name (My::Module -> My-Module)
    (my $distname = $name) =~ s/::/-/g;
    
    # Get INST_LIB and installation directories
    # Use INSTALL_BASE for consistency with where we actually install modules
    my $inst_lib = $args->{INST_LIB} || 'blib/lib';
    my $installsitelib = $args->{INSTALLSITELIB} || $INSTALL_BASE;
    
    # Build install commands for module/data/share files
    my @install_cmds;
    my @blib_cmds;     # Also copy to blib/lib for test compatibility
    my %dirs_seen;
    my %blib_dirs_seen;
    for my $src (sort keys %$pm) {
        my $dest = $pm->{$src};
        my $dir = dirname($dest);
        unless ($dirs_seen{$dir}++) {
            push @install_cmds, _shell_mkdir($dir);
        }
        push @install_cmds, _shell_cp($src, $dest);
        
        # Build blib/lib copy command: derive relative path from source
        # Source is like "lib/Text/CSV.pm" -> blib dest is "blib/lib/Text/CSV.pm"
        my $blib_rel;
        if ($src =~ m{^lib/(.*)$}) {
            $blib_rel = $1;
        } elsif ($src =~ m{^blib/lib/(.*)$}) {
            $blib_rel = $1;
        } else {
            # Flat layout: compute from dest path relative to INSTALL_BASE
            ($blib_rel = $dest) =~ s{^\Q$INSTALL_BASE\E/?}{};
        }
        if ($blib_rel) {
            my $blib_dest = "blib/lib/$blib_rel";
            my $blib_dir = dirname($blib_dest);
            unless ($blib_dirs_seen{$blib_dir}++) {
                push @blib_cmds, _shell_mkdir($blib_dir);
            }
            push @blib_cmds, _shell_cp($src, $blib_dest);
        }
    }
    
    # Build install commands for scripts
    my @script_cmds;
    if ($scripts && %$scripts) {
        my %sdirs;
        for my $src (sort keys %$scripts) {
            my $dest = $scripts->{$src};
            my $dir = dirname($dest);
            unless ($sdirs{$dir}++) {
                push @script_cmds, _shell_mkdir($dir);
            }
            push @script_cmds, _shell_cp($src, $dest);
        }
    }

    # Also stage EXE_FILES into blib/script/ so that 'make test' can run
    # them from the blib tree (standard MakeMaker behavior). Tests like
    # Pod::Markdown's t/pod2markdown.t invoke blib/script/<name> directly.
    my @blib_script_cmds;
    if ($scripts && %$scripts) {
        my %bsdirs;
        for my $src (sort keys %$scripts) {
            my $blib_dest = File::Spec->catfile('blib', 'script', basename($src));
            my $blib_dir = dirname($blib_dest);
            unless ($bsdirs{$blib_dir}++) {
                push @blib_script_cmds, _shell_mkdir($blib_dir);
            }
            push @blib_script_cmds, _shell_cp($src, $blib_dest);
        }
    }

    my $install_cmds_str = join("\n", @install_cmds) || "\t\@true";
    my $blib_cmds_str = join("\n", @blib_cmds) || "\t\@true";
    my $script_cmds_str = join("\n", @script_cmds) || "\t\@true";
    my $blib_script_cmds_str = join("\n", @blib_script_cmds) || "\t\@true";
    my $file_count = scalar(keys %$pm) + scalar(keys %$scripts);
    
    # Build PL_FILES commands (prefixed with - so failures are non-fatal;
    # many .PL scripts generate optional CLI tools that aren't needed for
    # the module's core functionality)
    my @pl_cmds;
    if ($args->{PL_FILES} && %{$args->{PL_FILES}}) {
        for my $pl (sort keys %{$args->{PL_FILES}}) {
            my $target = $args->{PL_FILES}{$pl};
            if (ref $target eq 'ARRAY') {
                for my $t (@$target) {
                    push @pl_cmds, "\t-$perl $pl $t";
                }
            } else {
                push @pl_cmds, "\t-$perl $pl $target";
            }
        }
    }
    my $pl_cmds_str = join("\n", @pl_cmds) || "\t\@true";
    
    # Build PREREQ_PM comment (MakeMaker writes these for tools to parse)
    my $prereq_comment = '';
    if ($args->{PREREQ_PM} && %{$args->{PREREQ_PM}}) {
        my @prereqs;
        for my $mod (sort keys %{$args->{PREREQ_PM}}) {
            next if $mod eq 'perl';
            my $ver = $args->{PREREQ_PM}{$mod};
            $ver = 0 unless defined $ver;
            push @prereqs, "$mod=>q[$ver]";
        }
        if (@prereqs) {
            $prereq_comment = "#\tPREREQ_PM => { " . join(", ", @prereqs) . " }\n";
        }
    }
    
    print $fh <<"MAKEFILE";
# Makefile generated by PerlOnJava MakeMaker
# 'make'         builds into ./blib (no files installed to site libdir)
# 'make test'    runs the test harness against ./blib
# 'make install' copies ./blib files to INSTALLSITELIB
# This matches standard ExtUtils::MakeMaker semantics so that 'cpan -t'
# (test only) does not install, while 'cpan -i' (install) does.
$prereq_comment
NAME = $name
DISTNAME = $distname
VERSION = $version
PERL = $perl
INSTALLDIRS = site
INST_LIB = $inst_lib
INSTALLSITELIB = $installsitelib
NOECHO = \@
RM_RF = rm -rf

all:: pm_to_blib pure_all pl_files blib_scripts config
\t\@echo "PerlOnJava: $name v$version built ($file_count files in ./blib)"

# pm_to_blib: stage module/data files into ./blib/lib (matches standard
# ExtUtils::MakeMaker). Files are NOT copied to INSTALLSITELIB here;
# that happens in the 'install' target.
# Also create blib/arch so that "use blib" / "-Mblib" works (blib.pm requires both).
pm_to_blib::
\t\@mkdir -p blib/arch
$blib_cmds_str

# pure_all is an alias target some postambles (File::ShareDir::Install,
# Alien::Build) hook to add extra blib-staging steps. Depends on pm_to_blib.
pure_all:: pm_to_blib

# Stage EXE_FILES into blib/script/ so tests can invoke them via the blib tree
blib_scripts::
$blib_script_cmds_str

# Process PL_FILES
pl_files::
$pl_cmds_str

# Install executable scripts (called only from 'install')
install_scripts::
$script_cmds_str

# Use double-colon rules throughout so that postambles (e.g.
# Alien::Build's MY::postamble, File::ShareDir::Install) can add
# additional rules for the same target. Mixing : and :: is a fatal
# make error, and real ExtUtils::MakeMaker uses :: for these targets.
config::

test::
\t$test_cmd

# install: copy staged files from ./lib to INSTALLSITELIB, plus scripts.
# Depends on 'all' to ensure blib is built first (matches standard MakeMaker).
install:: all install_scripts
$install_cmds_str
\t\@echo "PerlOnJava: $name v$version installed ($file_count files) to \$(INSTALLSITELIB)"

clean::
\t\$(RM_RF) blib pm_to_blib

realclean:: clean
\t\$(RM_RF) $makefile ${makefile}.old

distclean:: clean
\t\$(RM_RF) $makefile ${makefile}.old

# ppd: real ExtUtils::MakeMaker generates a Win32 PPM .ppd descriptor here.
# PerlOnJava has no PPM, but some Makefile.PLs (e.g. MailTools) add
# `all:: ppd` in their postamble. Provide a no-op target so make doesn't
# fail with "No rule to make target ppd".
ppd::
\t\@true

.PHONY: all pm_to_blib pure_all pl_files blib_scripts config test install clean realclean distclean install_scripts ppd
MAKEFILE

    # Call MY::postamble if it exists (File::ShareDir::Install uses this)
    if (defined &MY::postamble) {
        my $postamble = MY::postamble($mm);
        if ($postamble) {
            print $fh "\n# Postamble from MY::postamble\n";
            print $fh $postamble;
            print $fh "\n";
        }
    }

    close $fh;
}

# Helper: generate a shell mkdir -p command for Makefile
sub _shell_mkdir {
    my ($dir) = @_;
    $dir =~ s/'/'\\''/g;  # escape single quotes
    return "\t\@mkdir -p '$dir'";
}

# Helper: generate a shell cp command for Makefile.
# Tolerant of missing source files: some distributions generate .pm files
# from .pm.PL scripts that require XS bootstrap (e.g. Term::ReadKey) and
# PerlOnJava cannot run them. We skip missing sources with a warning
# rather than failing the whole install.
sub _shell_cp {
    my ($src, $dest) = @_;
    $src =~ s/'/'\\''/g;
    $dest =~ s/'/'\\''/g;
    return "\t\@if [ -f '$src' ]; then rm -f '$dest' && cp '$src' '$dest'; else echo 'PerlOnJava: skipping missing source: $src'; fi";
}

# Write a tiny `Build` script that delegates to make targets. Some CPAN
# distributions ship a Build.PL whose only job is to delegate to Makefile.PL
# (Module::Install's "Dear Distribution Packager" trick, e.g. Data::Utilities).
# CPAN.pm sees Build.PL, sets $self->{modulebuild} = 1, and then expects to
# invoke `./Build`, `./Build test`, `./Build install`. Without a real
# Module::Build (which we don't run here because PerlOnJava MakeMaker took
# over WriteMakefile via Makefile.PL), no Build script is produced and the
# CPAN install fails with "no Build file available". This proxy bridges the
# gap by translating Module::Build subcommands to the corresponding make
# targets generated by _create_install_makefile.
sub _create_build_script_proxy {
    my $build = 'Build';
    open my $fh, '>', $build or do {
        warn "Note: Could not create Build script: $!\n";
        return;
    };
    print $fh <<'BUILD';
#!/usr/bin/env perl
# Generated by PerlOnJava MakeMaker as a Module::Build->make proxy.
# CPAN.pm runs this when a Build.PL was used to configure the dist.
use strict;
use warnings;
my $cmd  = shift @ARGV;
$cmd = 'all' if !defined $cmd || $cmd eq '' || $cmd eq 'build';
# Map Module::Build subcommands to make targets. Anything unknown is
# passed through verbatim so e.g. './Build distclean' still works.
my %map = (
    all       => 'all',
    test      => 'test',
    install   => 'install',
    clean     => 'clean',
    realclean => 'realclean',
    distclean => 'distclean',
    config    => 'config',
);
my $target = exists $map{$cmd} ? $map{$cmd} : $cmd;
my @make = ($ENV{MAKE} || 'make', $target, @ARGV);
exec { $make[0] } @make
    or die "PerlOnJava Build proxy: failed to exec '@make': $!\n";
BUILD
    close $fh;
    chmod 0755, $build;
}

sub _create_mymeta {
    my ($name, $version, $args) = @_;

    # Create MYMETA.yml for CPAN.pm dependency resolution.
    # Uses meta-spec v1.4 with flat top-level requires / build_requires keys.
    # CPAN.pm's prereq_pm() read_yaml fallback (Tier 2) only understands v1;
    # the previous v2 nested prereqs structure was silently ignored, causing
    # CPAN.pm to install modules without their dependencies.

    my $mymeta = 'MYMETA.yml';

    open my $fh, '>', $mymeta or do {
        warn "Note: Could not create MYMETA.yml: $!\n";
        return;
    };

    # Collect runtime requires (PREREQ_PM)
    my $requires = '';
    if ($args->{PREREQ_PM} && %{$args->{PREREQ_PM}}) {
        for my $mod (sort keys %{$args->{PREREQ_PM}}) {
            next if $mod eq 'perl';
            my $ver = $args->{PREREQ_PM}{$mod} || 0;
            $requires .= "  $mod: '$ver'\n";
        }
    }

    # Collect build + test requires (v1.4 has no separate test phase, merge them)
    my %breq;
    for my $key (qw(BUILD_REQUIRES TEST_REQUIRES)) {
        if ($args->{$key} && %{$args->{$key}}) {
            for my $mod (keys %{$args->{$key}}) {
                next if $mod eq 'perl';
                my $ver = $args->{$key}{$mod} || 0;
                $breq{$mod} = $ver;
            }
        }
    }
    my $build_requires = '';
    for my $mod (sort keys %breq) {
        $build_requires .= "  $mod: '$breq{$mod}'\n";
    }

    # Collect configure requires (default to ExtUtils::MakeMaker)
    my %creq = ('ExtUtils::MakeMaker' => 0);
    if ($args->{CONFIGURE_REQUIRES} && %{$args->{CONFIGURE_REQUIRES}}) {
        for my $mod (keys %{$args->{CONFIGURE_REQUIRES}}) {
            next if $mod eq 'perl';
            $creq{$mod} = $args->{CONFIGURE_REQUIRES}{$mod} || 0;
        }
    }
    my $configure_requires = '';
    for my $mod (sort keys %creq) {
        $configure_requires .= "  $mod: '$creq{$mod}'\n";
    }

    my $abstract = $args->{ABSTRACT} || "$name module";
    $abstract =~ s/'/''/g;  # YAML single-quote escape: double the quote

    (my $distname = $name) =~ s/::/-/g;

    # Remove trailing newlines to avoid blank lines between YAML sections
    chomp $build_requires;
    chomp $configure_requires;
    chomp $requires;

    # Format section headers: "key:\n  items" when non-empty, "key: {}" when empty
    my $br_section = $build_requires
        ? "build_requires:\n$build_requires" : "build_requires: {}";
    my $cr_section = $configure_requires
        ? "configure_requires:\n$configure_requires" : "configure_requires: {}";
    my $rq_section = $requires
        ? "requires:\n$requires" : "requires: {}";

    print $fh <<"MYMETA";
---
abstract: '$abstract'
author:
  - 'Unknown'
$br_section
$cr_section
dynamic_config: 0
generated_by: 'ExtUtils::MakeMaker version $VERSION'
license: perl
meta-spec:
  url: http://module-build.sourceforge.net/META-spec-v1.4.html
  version: '1.4'
name: $distname
no_index:
  directory:
    - t
    - inc
$rq_section
version: '$version'
MYMETA

    close $fh;
}

sub prompt {
    my ($msg, $default) = @_;
    $default //= '';
    print "$msg [$default] ";
    my $answer = <STDIN>;
    chomp $answer if defined $answer;
    return (defined $answer && $answer ne '') ? $answer : $default;
}

# Format a value for display (used by some Makefile.PL scripts)
sub neatvalue {
    my ($val) = @_;
    return 'undef' unless defined $val;
    return "'$val'" if $val =~ /\D/;
    return $val;
}

# Positional sprintf variant: %1$s, %2$s etc.
# Used by MM_Any.pm and other ExtUtils modules
sub _sprintf562 {
    my ($format, @args) = @_;
    for (my $i = 1; $i <= @args; ++$i) {
        $format =~ s/%${i}\$s/$args[$i - 1]/g;
    }
    $format;
}

#############################################################################
# Stub MM object for installed modules
#############################################################################
package PerlOnJava::MM::Installed;

use Config;

sub new { 
    my ($class, $args) = @_;
    bless { args => $args }, $class;
}

sub flush { 1 }

# Methods needed by File::ShareDir::Install postamble
sub oneliner {
    my ($self, $code, $switches) = @_;
    $switches ||= [];
    my $perl = $^X;
    # Remove leading/trailing whitespace and newlines from code
    $code =~ s/^\s+//;
    $code =~ s/\s+$//;
    # Replace internal newlines with semicolons for proper one-liner
    $code =~ s/\n/; /g;
    # Escape quotes in code
    $code =~ s/'/'\\''/g;
    my $sw = join(' ', @$switches);
    return qq{$perl $sw -e '$code'};
}

sub split_command {
    my ($self, $cmd, @args) = @_;
    # For simplicity, return a single command with all args
    # Real MakeMaker splits long commands to avoid command line limits
    return $cmd . ' ' . join(' ', @args);
}

sub quote_literal {
    my ($self, $text) = @_;
    return $text if !defined $text;
    # Simple quoting - escape special characters
    $text =~ s/([\\'])/\\$1/g;
    return "'$text'";
}

sub catfile {
    my ($self, @parts) = @_;
    return File::Spec->catfile(@parts);
}

# No-op methods that Makefile.PL might call
sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    # Silently ignore unknown method calls
    return;
}

sub DESTROY {}

#############################################################################
# Stub MM object for XS modules (not installed)
#############################################################################
package PerlOnJava::MM::XSStub;

sub new { 
    my ($class, $name, $xs_files, $args) = @_;
    bless { name => $name, xs => $xs_files, args => $args }, $class;
}

sub flush { 
    my $self = shift;
    print "Skipped XS module: $self->{name}\n";
    return 0;
}

sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    return;
}

sub DESTROY {}

1;

__END__

=head1 NAME

ExtUtils::MakeMaker - PerlOnJava implementation

=head1 SYNOPSIS

    # In Makefile.PL
    use ExtUtils::MakeMaker;
    
    WriteMakefile(
        NAME         => 'My::Module',
        VERSION_FROM => 'lib/My/Module.pm',
        PREREQ_PM    => { 'Some::Module' => 0 },
    );

=head1 DESCRIPTION

This is a PerlOnJava-specific implementation of ExtUtils::MakeMaker.
Instead of generating a Makefile for C compilation, it:

=over 4

=item *

For pure Perl modules: directly copies .pm files to the installation directory

=item *

For XS/C modules: prints guidance on how to port to Java

=back

=head1 ENVIRONMENT VARIABLES

=over 4

=item PERLONJAVA_LIB

Installation directory for modules. Defaults to ./lib or relative to the JAR.

=back

=head1 SEE ALSO

L<docs/guides/using-cpan-modules.md> for information on adding CPAN modules.

=cut
