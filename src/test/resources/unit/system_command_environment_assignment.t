use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'POSIX shell environment assignments are not Windows syntax'
    if $^O eq 'MSWin32';

plan tests => 3;

my $dir = tempdir(CLEANUP => 1);
my $script = "$dir/print-env";
open my $fh, '>', $script or die "open $script: $!";
print {$fh} "#!$^X\nprint((\$ENV{JPERL_QX_ENV_ASSIGNMENT} // q{}) . qq{\\n});\n";
close $fh or die "close $script: $!";
chmod 0755, $script or die "chmod $script: $!";

my $output = qx{JPERL_QX_ENV_ASSIGNMENT=value $script};

is($output, "value\n", 'qx applies a leading environment assignment to the child');
is($?, 0, 'qx reports a successful child exit status');
ok(!exists $ENV{JPERL_QX_ENV_ASSIGNMENT}, 'qx does not change the parent environment');
