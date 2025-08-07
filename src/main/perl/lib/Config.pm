package Config;
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

# Normalize OS name
$os_name = lc($os_name);
$os_name =~ s/\s+/_/g;

# tie returns the object, so the value returned to require will be true.
%Config = (
    archname => "java-$java_version-$os_arch",
    osname => $os_name,
    osvers => $os_version,

    # PerlOnJava specific
    perlonjava => '3.0.0',
    java_version => $java_version,
    java_vendor => $java_vendor,
    java_home => $java_home,

    # Compiler settings (Java instead of C)
    cc => 'javac',
    ld => 'javac',

    # Library/path configuration
    path_sep => $path_separator,
    file_sep => $file_separator,

    # User directories
    home => $user_home,
    pwd => $user_dir,

    # Standard Perl paths (relative to jar or filesystem)
    archlibexp => 'perlonjava/lib/perl5/5.42.0/' . "java-$java_version-$os_arch",
    privlibexp => 'perlonjava/lib/perl5/5.42.0',
    sitearchexp => 'perlonjava/lib/perl5/site_perl/5.42.0/' . "java-$java_version-$os_arch",
    sitelibexp => 'perlonjava/lib/perl5/site_perl/5.42.0',
    vendorarchexp => 'perlonjava/lib/perl5/vendor_perl/5.42.0/' . "java-$java_version-$os_arch",
    vendorlibexp => 'perlonjava/lib/perl5/vendor_perl/5.42.0',

    # Script directory
    scriptdir => 'perlonjava/bin',

    # Dynamic loading (Java uses classloading)
    dlext => 'jar',
    dlsrc => 'classloader',
    so => 'jar',

    ## # Threading
    ## useithreads => 'define',
    ## usethreads => 'define',

    # Sizes (Java platform - guaranteed minimums)
    intsize => '4',
    longsize => '8',
    ptrsize => '8',
    doublesize => '8',
    byteorder => _determine_byteorder(),

    ivsize => 4,
    lseeksize => 8,

    # Features available in PerlOnJava
    d_readlink => 'define',
    d_symlink => _check_symlink_support(),
    d_fork => 'undef',  # No true fork in Java
    d_alarm => 'undef', # No reliable alarm in Java
    d_chown => _check_chown_support(),
    d_chroot => 'undef',
    d_crypt => 'define',

    # Signal handling
    sig_name => 'HUP INT QUIT ILL TRAP ABRT BUS FPE KILL USR1 SEGV USR2 PIPE ALRM TERM',
    sig_num => '1 2 3 4 5 6 7 8 9 10 11 12 13 14 15',

    # Executable
    exe_ext => $os_name =~ /win/ ? '.exe' : '',
    _exe => $os_name =~ /win/ ? '.exe' : '',

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

    # Perl tests use this
    useperlio => 'define',
);

# Helper functions
sub _determine_byteorder {
    # Java is big-endian in its bytecode, but native byte order varies
    # This is a simplified check
    my $test = pack("L", 0x12345678);
    my @bytes = unpack("C4", $test);
    if ($bytes[0] == 0x78) {
        return "1234";  # little-endian
    } elsif ($bytes[0] == 0x12) {
        return "4321";  # big-endian
    } else {
        return "unknown";
    }
}

sub _check_symlink_support {
    # Check if the OS supports symbolic links
    my $os = lc(getProperty('os.name') || '');
    return 'undef' if $os =~ /win/;
    return 'define';
}

sub _check_chown_support {
    # Check if the OS supports chown
    my $os = lc(getProperty('os.name') || '');
    return 'undef' if $os =~ /win/;
    return 'define';
}

1;
