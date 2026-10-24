use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempfile);

my ($capture, $path) = tempfile(UNLINK => 1);
{
    local *STDOUT;
    open STDOUT, '>>', $path or die "open captured STDOUT: $!";
    print "captured through localized STDOUT\n";
}

seek $capture, 0, 0 or die "seek capture file: $!";
my $captured = <$capture>;
is($captured, "captured through localized STDOUT\n",
    'implicit print follows a localized and reopened STDOUT glob');

