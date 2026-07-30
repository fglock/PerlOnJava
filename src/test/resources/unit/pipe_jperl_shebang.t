use strict;
use warnings;
use Test::More tests => 3;
use File::Spec;

my $script = File::Spec->catfile(
    File::Spec->tmpdir,
    "perlonjava-pipe-shebang-$$.pl",
);
my $output = "$script.out";

open my $source, '>', $script or die "open $script: $!";
print {$source} "#!$^X\n";
print {$source} "use strict;\n";
print {$source} 'my $out = shift; open my $fh, q{>}, $out or die $!; ';
print {$source} 'print {$fh} join q{}, <STDIN>; close $fh;' . "\n";
close $source;
chmod 0755, $script;

open my $pipe, "| $script $output" or die "pipe $script: $!";
print {$pipe} "through jperl shebang\n";
ok(close($pipe), 'output pipe closes successfully');

open my $result, '<', $output or die "open $output: $!";
is(join('', <$result>), "through jperl shebang\n", 'script receives pipe input');
close $result;

ok(unlink($script, $output), 'temporary files removed');
