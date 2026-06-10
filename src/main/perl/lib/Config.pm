package Config;

#
# Config.pm - PerlOnJava configuration module
#
# This module provides access to configuration values for the PerlOnJava runtime.
# It is designed to be compatible with Perl's Config module but provides
# values specific to the Java environment.
#
# Author: Flavio S. Glock
#

use strict;
use warnings;
use Java::System qw(getProperty getenv);

our ( %Config, $VERSION );

$VERSION = "5.042000";

# Skip @Config::EXPORT because it only contains %Config, which we special
# case below as it's not a function. @Config::EXPORT won't change in the
# lifetime of Perl 5.
my %Export_Cache = (myconfig => 1, config_sh => 1, config_vars => 1,
    config_re => 1, compile_date => 1, local_patches => 1,
    bincompat_options => 1, non_bincompat_options => 1,
    header_files => 1);

@Config::EXPORT = qw(%Config);
@Config::EXPORT_OK = keys %Export_Cache;

# Define our own import method to avoid pulling in the full Exporter:
sub import {
    shift;
    @_ = @Config::EXPORT unless @_;

    my @funcs = grep $_ ne '%Config', @_;
    my $export_Config = @funcs < @_ ? 1 : 0;

    no strict 'refs';
    my $callpkg = caller(0);
    foreach my $func (@funcs) {
        die qq{"$func" is not exported by the Config module\n}
            unless $Export_Cache{$func};
        *{$callpkg.'::'.$func} = \&{$func};
    }

    *{"$callpkg\::Config"} = \%Config if $export_Config;
    return;
}

die "$0: Perl lib version (5.42.0) doesn't match executable '$^X' version ($])"
    unless $^V;

$^V eq 5.42.0
    or die sprintf "%s: Perl lib version (5.42.0) doesn't match executable '$^X' version (%vd)", $0, $^V;


# Get Java system properties using Java::System module
my $java_version = getProperty('java.version') || '21';
my $java_vendor = getProperty('java.vendor') || 'Unknown';
my $os_name = getProperty('os.name') || 'Java';
my $os_arch = getProperty('os.arch') || 'jvm';
my $os_version = getProperty('os.version') || 'unknown';
my $file_separator = getProperty('file.separator') || '/';
my $path_separator = getProperty('path.separator') || ':';
my $user_home = getProperty('user.home') || '';
my $user_dir = getProperty('user.dir') || '';
my $java_home = getProperty('java.home') || '';
my $user_name = getProperty('user.name') || 'unknown';
my $perlonjava_home = $user_home
    ? _catdir($file_separator, $user_home, '.perlonjava')
    : '.perlonjava';
my $core_privlib = _catdir($file_separator, $perlonjava_home, 'core', 'lib', 'perl5', '5.42.0');
my $core_archlib = _catdir($file_separator, $core_privlib, "java-$java_version-$os_arch");
_ensure_dir(_catdir($file_separator, $core_archlib, 'CORE'));

sub _perl_os_name {
    my ($name) = @_;
    my $lc = lc($name || 'unknown');
    return 'MSWin32' if $lc =~ /^win/;
    return 'darwin'  if $lc =~ /^(?:mac|darwin)/;
    return 'linux'   if $lc =~ /(?:nix|nux|linux)/;
    return 'solaris' if $lc =~ /(?:sunos|solaris)/;
    return 'aix'     if $lc =~ /aix/;
    return 'freebsd' if $lc =~ /freebsd/;
    return 'openbsd' if $lc =~ /openbsd/;
    $lc =~ s/\s+//g;
    return $lc;
}

sub _perl_launcher_suffix {
    my ($is_windows, $perl_path) = @_;
    return '' unless $is_windows;
    return lc $1 if defined($perl_path) && $perl_path =~ /(\.(?:bat|cmd|exe))\z/i;
    return '.bat';
}

sub _shell_single_quote {
    my ($value) = @_;
    $value =~ s/'/'"'"'/g;
    return "'$value'";
}

# Best-effort hostname; falls back to "localhost" if Java doesn't expose it.
my $host_name = eval {
    require Sys::Hostname;
    Sys::Hostname::hostname();
} || 'localhost';

# Detect the real system C compiler by walking PATH directly.
# This is needed so that Makefile.PL files which run "$Config{cc} -o ..."
# to test for C library availability (e.g. Gzip::Faster checks for zlib)
# actually invoke a working C compiler instead of javac.
#
# We deliberately avoid backticks / system() here: spawning a subprocess
# (e.g. `which cc`) is slow, sets $?, and is not portable (Windows has no
# 'which').  Walking $ENV{PATH} in pure Perl is fast, leaves $? untouched,
# and works on every platform.
my $system_cc = do {
    my $is_win = lc($os_name) =~ /win/;
    my @candidates = $is_win ? qw(cl cc gcc) : qw(cc gcc clang);
    my $sep        = $is_win ? ';' : ':';
    my @path_dirs  = split /\Q$sep\E/, ($ENV{PATH} // '');
    my $found      = '';
    SEARCH: for my $candidate (@candidates) {
        for my $dir (@path_dirs) {
            # On Windows executables may have a .exe suffix
            for my $suffix ($is_win ? ('', '.exe', '.cmd') : ('')) {
                if (-x "$dir/$candidate$suffix") {
                    $found = $candidate;
                    last SEARCH;
                }
            }
        }
    }
    $found || ($is_win ? 'cl' : 'cc');
};

# Normalize OS name to Perl's $^O conventions.
$os_name = _perl_os_name($os_name);
my $is_windows = $os_name eq 'MSWin32';
my $perl_launcher_suffix = _perl_launcher_suffix($is_windows, $^X);
my $startperl = $is_windows
    ? '#!' . $^X
    : "#!/bin/sh\n"
        . 'eval "exec ' . _shell_single_quote($^X) . ' -x \"\$0\" \"\$@\""' . "\n"
        . "    if 0;\n"
        . "#!perl";

# tie returns the object, so the value returned to require will be true.
%Config = (
    archname => "java-$java_version-$os_arch",
    myarchname => "$os_arch-$os_name",
    osname => $os_name,
    osvers => $os_version,

    # PerlOnJava specific
    perlonjava => '5.42.0',
    java_version => $java_version,
    java_vendor => $java_vendor,
    java_home => $java_home,

    # Compiler settings
    # cc/ld report the *system* C compiler so that Makefile.PL probes
    # (e.g. in Gzip::Faster) that test C compilation with "$Config{cc} -o ..."
    # actually invoke a real C compiler rather than javac.  PerlOnJava's
    # MakeMaker (MM_PerlOnJava) still skips all XS/C build steps, so setting
    # these to the real compiler does not accidentally enable native builds.
    cc => $system_cc,
    ld => $system_cc,
    # ccflags includes -DSILENT_NO_TAINT_SUPPORT because PerlOnJava does not
    # implement full taint checking. This allows tests that check for taint
    # support to skip gracefully.
    ccflags => '-DSILENT_NO_TAINT_SUPPORT',
    ldflags => '',
    lddlflags => '',
    optimize => '',

    # Library/path configuration
    path_sep => $path_separator,
    file_sep => $file_separator,
    more => 'more',

    # User directories
    home => $user_home,
    pwd => $user_dir,

    # Build / maintainer identity. Real perl populates these at Configure
    # time. Under PerlOnJava there is no Configure, so we synthesise sane
    # defaults from the running JVM. They show up in Pod::Html output
    # (<link rev="made" href="mailto:...">), in test fixtures that
    # interpolate $Config{perladmin}, and in the like.
    perladmin => "$user_name\@$host_name",
    cf_email  => "$user_name\@$host_name",
    cf_by     => $user_name,
    myhostname => $host_name,

    # Standard Perl paths. The core exp paths must be real directories because
    # CPAN build helpers such as ExtUtils::CBuilder probe $archlibexp/CORE.
    archlibexp => $core_archlib,
    privlibexp => $core_privlib,
    sitearchexp => 'perlonjava/lib/perl5/site_perl/5.42.0/' . "java-$java_version-$os_arch",
    sitelibexp => 'perlonjava/lib/perl5/site_perl/5.42.0',
    vendorarchexp => 'perlonjava/lib/perl5/vendor_perl/5.42.0/' . "java-$java_version-$os_arch",
    vendorlibexp => 'perlonjava/lib/perl5/vendor_perl/5.42.0',

    # Script directory (JAR-embedded scripts at /bin/)
    scriptdir => 'jar:PERL5BIN',
    scriptdirexp => 'jar:PERL5BIN',

    # Dynamic loading (Java uses classloading)
    dlext => 'jar',
    dlsrc => 'classloader',
    so => 'jar',

    # File locking (supported via Java's FileLock API)
    d_flock => 'define',
    d_fcntl_can_lock => 'define',

    ## # Threading
    ## useithreads => 'define',
    ## usethreads => 'define',

    # Sizes (Java platform - 32-bit integer model)
    # PerlOnJava uses Java int (32-bit) as the native integer type.
    # ivsize=4 signals a 32-bit Perl, so tests skip 64-bit-only paths.
    shortsize => '2',
    intsize => '4',
    longsize => '8',
    ptrsize => '8',
    doublesize => '8',
    uvsize => '4',
    sizesize => '4',
    byteorder => _determine_byteorder(),

    ivsize => 4,
    lseeksize => 8,

    # Type names (matching a 32-bit Perl on LP64 platform)
    ivtype => 'int',
    uvtype => 'unsigned int',
    nvtype => 'double',
    i8type => 'signed char',
    u8type => 'unsigned char',
    i16type => 'short',
    u16type => 'unsigned short',
    i32type => 'int',
    u32type => 'unsigned int',

    # 64-bit integer support - not enabled (32-bit integer model)
    # use64bitint and d_quad are left undef so tests correctly skip
    # 64-bit-only code paths.
    
    # nv_preserves_uv_bits: Number of bits in an unsigned integer that can be
    # preserved in a floating-point number (NV) without loss of precision.
    # For 32-bit systems with 32-bit integers (IV), this is typically 32.
    # This value is critical for pack/unpack checksum tests - when checksums
    # exceed this bit count, they may lose precision in floating-point math.
    # Tests use this to skip checksums that would overflow on this architecture.
    nv_preserves_uv_bits => 32,
    
    # d_nv_preserves_uv: Whether NV (double) can preserve UV (unsigned int) values
    # For 32-bit integers with 64-bit doubles (IEEE 754), this is true since
    # doubles have 53 bits of precision, which is more than 32 bits.
    d_nv_preserves_uv => 'define',

    # Features available in PerlOnJava
    d_readlink => 'define',
    d_symlink => _check_symlink_support(),
    d_fork => undef,  # No true fork in Java
    d_alarm => 'define', # We now have alarm support with signal queue
    d_chown => _check_chown_support(),
    d_chroot => undef,
    d_crypt => 'define',
    d_double_has_inf => 'define',
    d_double_has_nan => 'define',
    d_double_style_ieee => 'define',

    # Directory handles — we implement opendir/readdir/telldir/closedir
    # via java.nio. Devel::Symdump (and similar introspection modules)
    # branch on these to choose between telldir() and B::IO::IoTYPE
    # introspection, so they need to be advertised honestly.
    d_telldir   => 'define',
    d_seekdir   => 'define',
    d_rewinddir => 'define',
    d_readdir   => 'define',
    
    # Socket support - we have implemented socket operators
    d_socket => 'define',
    d_getpbyname => 'define',
    d_gethbyname => 'define',
    d_sockpair => undef,  # Not implemented yet
    d_oldsock => undef,
    
    # Network functions
    d_gethostbyname => 'define',
    d_getprotobyname => 'define',
    d_getservbyname => 'define',

    # Signal handling - signal 0 is ZERO (used for process existence checks)
    # Note: Signal names vary by OS. This is a common POSIX subset.
    # The index in the space-separated list corresponds to the signal number.
    sig_name => ($is_windows
        ? 'ZERO INT ILL FPE SEGV TERM ABRT BREAK'
        : 'ZERO HUP INT QUIT ILL TRAP ABRT BUS FPE KILL USR1 SEGV USR2 PIPE ALRM TERM'),
    sig_num => ($is_windows
        ? '0 2 4 8 11 15 22 21'
        : '0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15'),

    # Executable
    obj_ext => '.o',
    exe_ext => $is_windows ? '.exe' : '',
    _exe => $perl_launcher_suffix,
    perlpath => $^X,  # Path to the perl interpreter (jperl)
    startperl => $startperl,  # Shebang line for Perl scripts
    sharpbang => '#!',  # Shebang prefix
    eunicefix => ':',   # No-op fixer (only used on EUNICE)

    # Version info
    version => '5.42.0',
    version_patchlevel_string => 'version 42 patchlevel 0',
    api_version => '42',
    api_subversion => '0',
    api_versionstring => '5.42.0',

    # Build configuration
    dont_use_nlink => undef,
    usevendorprefix => undef,
    usesitecustomize => 'define',

    # Include paths (empty for Java)
    inc_version_list => '',

    # Library paths (Java classpath)
    libpth => '',
    ldlibpthname => 'CLASSPATH',

    # Make/build tools
    make => 'make',
    gmake => 'gmake',

    # Install prefixes
    prefix => '/usr/local',
    prefixexp => '/usr/local',
    installprefix => '/usr/local',
    installprefixexp => '/usr/local',
    
    # Site installation paths (for user-installed modules via jcpan)
    siteprefix => $user_home . '/.perlonjava',
    siteprefixexp => $user_home . '/.perlonjava',
    installsitelib => $user_home . '/.perlonjava/lib',
    installsitearch => $user_home . '/.perlonjava/lib',
    installsitebin => $user_home . '/.perlonjava/bin',
    installsitescript => $user_home . '/.perlonjava/bin',
    installsiteman1dir => $user_home . '/.perlonjava/man/man1',
    installsiteman3dir => $user_home . '/.perlonjava/man/man3',
    
    # Core installation paths (read-only, in JAR)
    installprivlib => 'jar:PERL5LIB',
    installarchlib => 'jar:PERL5LIB',
    installbin => 'jar:PERL5BIN',
    installscript => 'jar:PERL5BIN',
    installman1dir => $user_home . '/.perlonjava/man/man1',
    installman3dir => $user_home . '/.perlonjava/man/man3',

    # Man page directories
    man1dir => $user_home . '/.perlonjava/man/man1',
    man3dir => $user_home . '/.perlonjava/man/man3',
    man1direxp => $user_home . '/.perlonjava/man/man1',
    man3direxp => $user_home . '/.perlonjava/man/man3',
    siteman1dir => $user_home . '/.perlonjava/man/man1',
    siteman3dir => $user_home . '/.perlonjava/man/man3',
    siteman1direxp => $user_home . '/.perlonjava/man/man1',
    siteman3direxp => $user_home . '/.perlonjava/man/man3',

    # Man page section suffixes
    man1ext => '1',
    man3ext => '3pm',

    # Perl tests use this
    useperlio => 'define',
    
    # Extensions available in PerlOnJava
    extensions => 'Fcntl IO File/Glob Socket IO::Socket',
    dynamic_ext => 'Fcntl IO File/Glob Socket IO::Socket',
    static_ext => '',
    
    # File operations
    d_truncate => 'define',  # We have truncate support
    d_ftruncate => 'define',
);

sub non_bincompat_options() {}
sub bincompat_options() {}

sub _catdir {
    my ($sep, @parts) = @_;
    my $path = shift @parts;
    for my $part (@parts) {
        next unless defined $part && length $part;
        $path =~ s/\Q$sep\E+\z//;
        $path .= $sep . $part;
    }
    return $path;
}

sub _ensure_dir {
    my ($dir) = @_;
    return if -d $dir;

    my $sep = $file_separator;
    my @parts = grep length, split /\Q$sep\E+/, $dir;
    my $current = $dir =~ /^\Q$sep\E/ ? $sep : '';

    for my $part (@parts) {
        $current = length($current) && $current ne $sep
            ? _catdir($sep, $current, $part)
            : $current . $part;
        mkdir $current unless -d $current;
    }
}

# Return a string describing the perl configuration (like perl -V)
sub myconfig {
    my $config = "Summary of my perl5 (revision 5 version 42 subversion 0) configuration:\n";
    $config .= "   \n";  # Blank line with leading spaces (matches Perl format)
    $config .= "  Platform:\n";
    $config .= "    osname=$Config{osname}\n";
    $config .= "    osvers=$Config{osvers}\n";
    $config .= "    archname=$Config{archname}\n";
    $config .= "  Compiler:\n";
    $config .= "    cc=$Config{cc}\n";
    $config .= "  Linker and Libraries:\n";
    $config .= "    ld=$Config{ld}\n";
    $config .= "    so=$Config{so}\n";
    $config .= "  Dynamic Linking:\n";
    $config .= "    dlext=$Config{dlext}\n";
    $config .= "\n\n";  # Trailing newlines to match Perl format
    return $config;
}

# Helper functions
sub _determine_byteorder {
    my $test = pack("L", 0x12345678);
    my @bytes = unpack("C4", $test);
    if ($bytes[0] == 0x78) {
        return "1234";  # little-endian (32-bit)
    } elsif ($bytes[0] == 0x12) {
        return "4321";  # big-endian (32-bit)
    } else {
        return "unknown";
    }
}

sub _check_symlink_support {
    # Check if the OS supports symbolic links
    my $os = lc(getProperty('os.name') || '');
    return undef if $os =~ /win/;
    return 'define';
}

sub _check_chown_support {
    # Check if the OS supports chown
    my $os = lc(getProperty('os.name') || '');
    return undef if $os =~ /win/;
    return 'define';
}

1;
