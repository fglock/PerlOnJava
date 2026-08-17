use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 2;

my ($seed, $path) = tempfile(UNLINK => 0);
close $seed or die "close seed: $!";

my $fd;
{
    open my $out, '>', $path or die "open write: $!";
    $fd = fileno($out);
    print {$out} "scope-exit-payload" or die "write: $!";
}

my $dup_ok = open my $dup, ">&$fd";
close $dup if $dup_ok;
unlink $path or die "unlink $path: $!";
ok(defined($fd), 'filehandle received an fd');
ok(!$dup_ok, 'fd cannot be reopened after lexical scope exit');
