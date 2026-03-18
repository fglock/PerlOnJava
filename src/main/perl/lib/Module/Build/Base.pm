package Module::Build::Base;

# PerlOnJava workaround: Override have_forkpipe to disable fork pipes
# JVM doesn't support true fork(), so we make Module::Build use backticks instead

use strict;
use warnings;
use Cwd qw(abs_path);

# Remove this file from %INC so the real Module::Build::Base can be loaded
delete $INC{'Module/Build/Base.pm'};

# Find and load the real Module::Build::Base
# We need to skip files that match our stub (in jar or this file)
my $loaded = 0;
for my $inc_path (@INC) {
    next if $inc_path =~ /^jar:/;  # Skip jar entries (that's where our stub is)
    next unless -d $inc_path;
    my $file = "$inc_path/Module/Build/Base.pm";
    if (-f $file) {
        # Ensure the inc_path is in @INC so dependencies can be found
        # (do() doesn't automatically add the directory to @INC like require does)
        unshift @INC, $inc_path unless grep { $_ eq $inc_path } @INC;
        
        # Get absolute path for do() since '.' is not in @INC
        my $abs_file = abs_path($file);
        
        # Load the real module using do() with absolute path
        my $result = do $abs_file;
        if ($@) {
            die "Error loading Module::Build::Base from $file: $@";
        }
        unless (defined $result) {
            die "Failed to load Module::Build::Base from $file: $!";
        }
        $INC{'Module/Build/Base.pm'} = $file;
        $loaded = 1;
        last;
    }
}

if (!$loaded) {
    # If we can't find a real Module::Build::Base, that's fine - just provide the stub
    # Define have_forkpipe to return 0
    *have_forkpipe = sub { 0 };
} else {
    # Now override have_forkpipe to return false
    # This makes _backticks() use backticks instead of fork+pipe
    no warnings 'redefine';
    *have_forkpipe = sub { 0 };
}

1;
