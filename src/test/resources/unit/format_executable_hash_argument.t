use strict;
use warnings;
use Test::More;

format FORMAT_EXECUTABLE_HASH_ARGUMENT =
@###
${{qw[ Sun 0 Mon 1 Tue 2 Wed 3 Thu 4 Fri 5 Sat 6 ]}}{"Wed"}
.

my $path = 'format_executable_hash_argument.tmp';
open my $fh, '>', $path or die "open $path: $!";
my $old_fh = select((select($fh), $~ = 'FORMAT_EXECUTABLE_HASH_ARGUMENT')[0]);
write $fh;
select $old_fh;
close $fh or die "close $path: $!";

open my $read_fh, '<', $path or die "read $path: $!";
my $rendered = do { local $/; <$read_fh> };
close $read_fh or die "close $path after read: $!";
unlink $path or die "unlink $path: $!";

is($rendered, "   3\n",
    'format argument evaluates a nested hash expression at write time');

done_testing;
