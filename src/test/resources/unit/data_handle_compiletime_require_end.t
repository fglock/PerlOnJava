use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempdir);

our $module_dir;
BEGIN {
    $module_dir = tempdir(CLEANUP => 1);
    mkdir "$module_dir/Local" or die "mkdir $module_dir/Local: $!";
    open my $module, '>', "$module_dir/Local/Issue1244Dependency.pm"
        or die "create dependency: $!";
    print {$module} <<'PERL';
{
    package Local::Issue1244Dependency;
    sub helper { 1 }
}
1;
__END__
This module's documentation must not affect the caller's DATA handle.
PERL
    close $module or die "close dependency: $!";
    unshift @INC, $module_dir;
}

use Local::Issue1244Dependency;

is(<DATA>, "sentinel\n",
   'compile-time require of module with __END__ preserves caller DATA');

__DATA__
sentinel
