use strict;
use warnings;

use File::Temp qw(tempfile);
use Test::More;

sub _set_tempfile {
    my ($text) = @_;
    my $temp = tempfile;
    select $temp;
    local $| = 1;
    select STDOUT;
    print {$temp} $text;
    seek $temp, 0, 0;
    return $temp;
}

my $line;
{
    local *STDIN = _set_tempfile("yes\n");
    my $output = tempfile;
    local *STDOUT;
    open STDOUT, ">&", fileno($output) or die "redirect STDOUT: $!";
    $line = <STDIN>;
}

is($line, "yes\n", 'localized STDIN survives a simultaneous STDOUT redirect');
done_testing;
