use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempfile);

my ($script, $path) = tempfile('die-end-status-XXXXXX', TMPDIR => 1, UNLINK => 1);
print {$script} <<'PERL' or die "write child script: $!";
END { print STDERR "END status=$?\n" }
die "uncaught failure\n";
PERL
close $script or die "close child script: $!";

my $output = qx{"$^X" "$path" 2>&1};
is($output, "uncaught failure\nEND status=255\n",
    'an uncaught die reports to STDERR and sets $? before END blocks run');
