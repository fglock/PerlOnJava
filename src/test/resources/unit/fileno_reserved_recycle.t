use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

open my $in, '<&=STDIN' or die "dup stdin: $!";
is(fileno($in), 0, 'stdin duplicate reports fd 0');
close $in or die "close stdin duplicate: $!";

open my $out, '>&=STDOUT' or die "dup stdout: $!";
is(fileno($out), 1, 'stdout duplicate reports fd 1');
close $out or die "close stdout duplicate: $!";

my $dir = tempdir(CLEANUP => 1);
open my $fh, '>', "$dir/regular.txt" or die "open regular: $!";
ok(fileno($fh) > 2, 'regular filehandle does not reuse reserved stdio fd');

done_testing();
