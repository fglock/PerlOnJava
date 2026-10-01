use strict;
use warnings;
use Test::More tests => 4;
use IPC::Open3;
use Symbol qw(gensym);
use Encode qw(encode);
use File::Temp qw(tempfile);
my $program = encode('UTF-8', 'use utf8; "$' . chr(0x3030) . '"');
my ($source, $path) = tempfile(SUFFIX => '.pl', UNLINK => 1);
binmode $source;
print {$source} $program;
close $source;
my ($out, $err) = (gensym(), gensym());
my $pid = open3(undef, $out, $err, $^X, $path);
local $/;
my $stdout = <$out> // '';
my $stderr = <$err> // '';
waitpid($pid, 0);
ok($? != 0, 'invalid wide source exits unsuccessfully');
is($stdout, '', 'compilation diagnostics do not leak to stdout');
unlike($stderr, qr/^Wide character in print\n/m,
    'host diagnostic output does not invent an unlocated Perl print warning');
like($stderr, qr/syntax error at (?:\Q$path\E|-) line 1, near "\$.*"\nExecution of (?:\Q$path\E|-) aborted due to compilation errors\.\n?\z/s,
    'wide compilation error retains its source and abort diagnostic');
