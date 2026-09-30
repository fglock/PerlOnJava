use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 6;

my ($fh) = tempfile();
my $old_default = select($fh);
my $selected_glob = select;
my $old_glob = select(STDOUT);
my $default_handle = select;

is($old_default, 'main::STDOUT', 'select(FILEHANDLE) returns the standard handle name');
is(ref $selected_glob, 'GLOB', 'select() returns the selected glob reference');
is($selected_glob, $fh, 'select() returns the selected filehandle');
is($old_glob, $fh, 'select(STDOUT) returns the previously selected filehandle');
is($default_handle, 'main::STDOUT', 'select() returns the standard handle name after restoring it');
is(ref \$default_handle, 'SCALAR', 'the standard handle name remains a scalar');

close $fh;
