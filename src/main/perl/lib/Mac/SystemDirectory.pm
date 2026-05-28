package Mac::SystemDirectory;

use strict;
use warnings;

require Exporter;

our @ISA = qw(Exporter);
our $VERSION = '0.14';

use constant NSUserDomainMask    => 1;
use constant NSLocalDomainMask   => 2;
use constant NSNetworkDomainMask => 4;
use constant NSSystemDomainMask  => 8;
use constant NSAllDomainsMask    => 0x0ffff;

use constant NSApplicationDirectory          => 1;
use constant NSDemoApplicationDirectory      => 2;
use constant NSDeveloperApplicationDirectory => 3;
use constant NSAdminApplicationDirectory     => 4;
use constant NSLibraryDirectory              => 5;
use constant NSDeveloperDirectory            => 6;
use constant NSUserDirectory                 => 7;
use constant NSDocumentationDirectory        => 8;
use constant NSDocumentDirectory             => 9;
use constant NSCoreServiceDirectory          => 10;
use constant NSDesktopDirectory              => 12;
use constant NSCachesDirectory               => 13;
use constant NSApplicationSupportDirectory   => 14;
use constant NSDownloadsDirectory            => 15;
use constant NSInputMethodsDirectory         => 16;
use constant NSMoviesDirectory               => 17;
use constant NSMusicDirectory                => 18;
use constant NSPicturesDirectory             => 19;
use constant NSPrinterDescriptionDirectory   => 20;
use constant NSSharedPublicDirectory         => 21;
use constant NSPreferencePanesDirectory      => 22;
use constant NSItemReplacementDirectory      => 99;
use constant NSAllApplicationsDirectory      => 100;
use constant NSAllLibrariesDirectory         => 101;

our @EXPORT_OK = qw(
    FindDirectory HomeDirectory TemporaryDirectory
    NSUserDomainMask NSLocalDomainMask NSNetworkDomainMask NSSystemDomainMask
    NSAllDomainsMask
    NSApplicationDirectory NSDemoApplicationDirectory
    NSDeveloperApplicationDirectory NSAdminApplicationDirectory
    NSLibraryDirectory NSDeveloperDirectory NSUserDirectory
    NSDocumentationDirectory NSDocumentDirectory NSCoreServiceDirectory
    NSDesktopDirectory NSCachesDirectory NSApplicationSupportDirectory
    NSDownloadsDirectory NSInputMethodsDirectory NSMoviesDirectory
    NSMusicDirectory NSPicturesDirectory NSPrinterDescriptionDirectory
    NSSharedPublicDirectory NSPreferencePanesDirectory
    NSItemReplacementDirectory NSAllApplicationsDirectory
    NSAllLibrariesDirectory
);

our %EXPORT_TAGS = (
    all        => [ @EXPORT_OK ],
    DomainMask => [ grep { /^NS.*DomainMask$/ } @EXPORT_OK ],
    Directory  => [ grep { /^NS.*Directory$/ } @EXPORT_OK ],
);

sub HomeDirectory {
    die "Usage: Mac::SystemDirectory::HomeDirectory()\n" if @_;

    return $ENV{HOME} if defined $ENV{HOME} && length $ENV{HOME};

    my @pw = getpwuid($<);
    return $pw[7] if @pw && defined $pw[7] && length $pw[7];

    return;
}

sub TemporaryDirectory {
    die "Usage: Mac::SystemDirectory::TemporaryDirectory()\n" if @_;

    for my $key (qw(TMPDIR TEMP TMP)) {
        return $ENV{$key} if defined $ENV{$key} && length $ENV{$key};
    }

    return '/tmp';
}

sub FindDirectory {
    die "Usage: Mac::SystemDirectory::FindDirectory(directory [, domain_mask])\n"
        if @_ < 1 || @_ > 2;

    my ($directory, $mask) = @_;
    $mask = NSUserDomainMask unless defined $mask;

    my @paths;
    for my $domain (_domains_for_mask($mask)) {
        my @domain_paths = _paths_for_domain($directory, $domain);
        push @paths, @domain_paths;
    }

    return wantarray ? @paths : $paths[0];
}

sub _domains_for_mask {
    my ($mask) = @_;
    return unless $mask;

    return qw(user local network system) if ($mask & NSAllDomainsMask) == NSAllDomainsMask;

    my @domains;
    push @domains, 'user'    if $mask & NSUserDomainMask;
    push @domains, 'local'   if $mask & NSLocalDomainMask;
    push @domains, 'network' if $mask & NSNetworkDomainMask;
    push @domains, 'system'  if $mask & NSSystemDomainMask;
    return @domains;
}

sub _paths_for_domain {
    my ($directory, $domain) = @_;

    if ($directory == NSAllApplicationsDirectory) {
        return _all_domain_paths('Applications');
    }
    if ($directory == NSAllLibrariesDirectory) {
        return _all_domain_paths('Library');
    }

    if ($domain eq 'user') {
        return _user_paths($directory);
    }
    if ($domain eq 'local') {
        return _local_paths($directory);
    }
    if ($domain eq 'network') {
        return _network_paths($directory);
    }
    if ($domain eq 'system') {
        return _system_paths($directory);
    }

    return;
}

sub _home_dir {
    return HomeDirectory();
}

sub _user_paths {
    my ($directory) = @_;
    my $home = _home_dir();
    return unless defined $home;

    return "$home/Applications"                if $directory == NSApplicationDirectory;
    return "$home/Library"                     if $directory == NSLibraryDirectory;
    return "$home/Documents"                   if $directory == NSDocumentDirectory;
    return "$home/Desktop"                     if $directory == NSDesktopDirectory;
    return "$home/Library/Caches"              if $directory == NSCachesDirectory;
    return "$home/Library/Application Support" if $directory == NSApplicationSupportDirectory;
    return "$home/Downloads"                   if $directory == NSDownloadsDirectory;
    return "$home/Movies"                      if $directory == NSMoviesDirectory;
    return "$home/Music"                       if $directory == NSMusicDirectory;
    return "$home/Pictures"                    if $directory == NSPicturesDirectory;
    return "$home/Public"                      if $directory == NSSharedPublicDirectory;
    return "$home/Library/PreferencePanes"     if $directory == NSPreferencePanesDirectory;
    return "$home/Library/Input Methods"       if $directory == NSInputMethodsDirectory;

    return '/Users' if $directory == NSUserDirectory;
    return;
}

sub _local_paths {
    my ($directory) = @_;

    return '/Applications'                 if $directory == NSApplicationDirectory;
    return '/Library'                      if $directory == NSLibraryDirectory;
    return '/Library/Documentation'        if $directory == NSDocumentationDirectory;
    return '/Library/Caches'               if $directory == NSCachesDirectory;
    return '/Library/Application Support'  if $directory == NSApplicationSupportDirectory;
    return '/Library/Input Methods'        if $directory == NSInputMethodsDirectory;
    return '/Library/PreferencePanes'      if $directory == NSPreferencePanesDirectory;
    return '/Developer'                    if $directory == NSDeveloperDirectory;
    return '/Developer/Applications'       if $directory == NSDeveloperApplicationDirectory;
    return '/Applications/Utilities'       if $directory == NSAdminApplicationDirectory;

    return;
}

sub _network_paths {
    my ($directory) = @_;

    return '/Network/Applications' if $directory == NSApplicationDirectory;
    return '/Network/Library'      if $directory == NSLibraryDirectory;
    return '/Network/Users'        if $directory == NSUserDirectory;

    return;
}

sub _system_paths {
    my ($directory) = @_;

    return '/System/Applications'                if $directory == NSApplicationDirectory;
    return '/System/Library'                     if $directory == NSLibraryDirectory;
    return '/System/Library/CoreServices'        if $directory == NSCoreServiceDirectory;
    return '/System/Library/Documentation'       if $directory == NSDocumentationDirectory;
    return '/System/Library/Caches'              if $directory == NSCachesDirectory;
    return '/System/Library/Input Methods'       if $directory == NSInputMethodsDirectory;
    return '/System/Library/PreferencePanes'     if $directory == NSPreferencePanesDirectory;
    return '/System/Library/PrinterDescription'  if $directory == NSPrinterDescriptionDirectory;

    return;
}

sub _all_domain_paths {
    my ($leaf) = @_;
    my @paths;
    my $home = _home_dir();

    push @paths, "$home/$leaf" if defined $home;
    push @paths, "/$leaf";
    push @paths, "/Network/$leaf";
    push @paths, "/System/$leaf";

    return @paths;
}

1;

__END__

=head1 NAME

Mac::SystemDirectory - PerlOnJava shim for macOS standard directories

=head1 DESCRIPTION

This pure-Perl implementation provides enough of Mac::SystemDirectory's XS API
for CPAN modules that use it to locate common user directories.

=cut
