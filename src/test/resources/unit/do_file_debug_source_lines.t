use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempfile);

my ($fh, $path) = tempfile();
print {$fh} "my \$value = 'first line';\n\$value .= ' second line';\n1;\n";
close $fh or die "close $path: $!";

local $^P = 0x02;
my $loaded = do $path;

is($loaded, 1, 'do FILE executes successfully with debugger source retention enabled');
{
    no strict 'refs';
    is(scalar(@{"_<$path"}), 4, 'do FILE creates the debugger source array');
    ok(!defined(${"_<$path"}[0]), 'debugger source array reserves index zero as undef');
    is_deeply(
        [ @{"_<$path"}[1, 2] ],
        [ "my \$value = 'first line';\n", "\$value .= ' second line';\n" ],
        'debugger source array retains each do-file source line under its requested filename',
    );
}
