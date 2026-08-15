use strict;
use warnings;
use Test::More tests => 3;
use File::Temp qw(tempdir);

my $initial_position = tell(DATA);
ok($initial_position > 0, 'DATA starts after its source marker');

my $dir = tempdir(CLEANUP => 1);
mkdir "$dir/Local" or die "mkdir $dir/Local: $!";
open my $module, '>', "$dir/Local/DataHandleDependency.pm"
    or die "create dependency: $!";
print {$module} "package Local::DataHandleDependency; 1;\n";
close $module or die "close dependency: $!";

unshift @INC, $dir;
require Local::DataHandleDependency;

is(tell(DATA), $initial_position,
   'runtime require does not replace the caller DATA handle');
is(<DATA>, "payload\n", 'caller DATA remains readable after runtime require');

__DATA__
payload
