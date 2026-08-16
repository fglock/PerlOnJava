package Module::Build::Tiny;

use strict;
use warnings;
use Config ();
use Cwd qw(abs_path);

# Load the site-installed implementation, then replace only the XS build
# phase.  CPAN distributions still supply their normal .pm facade, while
# XSLoader decides at test time whether PerlOnJava has a Java/pure-Perl
# implementation for the extension.
delete $INC{'Module/Build/Tiny.pm'};
my $shim_file = abs_path(__FILE__);
my $loaded;
for my $inc_path (@INC) {
    next if $inc_path =~ /^jar:/;
    next unless -d $inc_path;
    my $file = "$inc_path/Module/Build/Tiny.pm";
    next unless -f $file;
    my $abs_file = abs_path($file);
    next if defined($shim_file) && defined($abs_file) && $abs_file eq $shim_file;
    my $result = do $abs_file;
    die "Error loading Module::Build::Tiny from $file: $@" if $@;
    die "Failed to load Module::Build::Tiny from $file: $!" unless defined $result;
    $INC{'Module/Build/Tiny.pm'} = $file;
    $loaded = 1;
    last;
}
die "Could not find the installed Module::Build::Tiny implementation"
    unless $loaded;

if ($Config::Config{perlonjava}) {
    no warnings 'redefine';
    *Module::Build::Tiny::process_xs = sub { return };
}

1;
