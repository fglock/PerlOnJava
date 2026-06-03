use strict;
use warnings;
use Test::More tests => 8;
use File::Temp qw(tempfile);
use PerlIO;

sub stable_layers {
    my ($handle, @args) = @_;
    return grep { $_ ne 'perlio' } PerlIO::get_layers($handle, @args);
}

my ($fh, $name) = tempfile();
is_deeply([ stable_layers($fh, output => 1) ], [ 'unix' ],
    'get_layers reports base unix layer for raw handle');

binmode($fh, ':utf8');
is_deeply([ stable_layers($fh, output => 1) ], [ 'unix', 'utf8' ],
    'get_layers reports unix plus active utf8 layer');

open(my $scalar_fh, '>', \(my $scalar_buffer)) or die "open scalar: $!";
is_deeply([ PerlIO::get_layers($scalar_fh, output => 1) ], [ 'scalar' ],
    'get_layers reports scalar layer for in-memory handle');
close($scalar_fh);

print {$fh} "Hi! \x{263a}\n";
seek($fh, 0, 0);
my @layers = PerlIO::get_layers($fh, output => 1);
binmode($fh, ':raw');
shift @layers;
binmode($fh, ':' . join(':', @layers));
my $captured = do { local $/; <$fh> };
is($captured, "Hi! \x{263a}\n",
    'Capture::Tiny-style relayering decodes utf8 capture data');
close($fh);
unlink $name;

my ($raw, $raw_name) = tempfile();
close($raw);
open(my $out, '>:encoding(UTF-16BE)', $raw_name) or die "open write: $!";
open(my $dup, '>&', $out) or die "dup: $!";
print {$dup} "A\x{263a}";
close($dup);
close($out);

open(my $in, '<:raw', $raw_name) or die "open raw: $!";
my $bytes = do { local $/; <$in> };
close($in);
unlink $raw_name;

is(unpack('H*', $bytes), '0041263a',
    'dup of layered handle preserves encoding pipeline');

SKIP: {
    skip 'nested jperl launcher is unavailable in Windows Maven CI', 1
        if $^O eq 'MSWin32';

    my ($sys_fh, $sys_name) = tempfile();
    binmode($sys_fh, ':utf8');
    my ($child_fh, $child_name) = tempfile(SUFFIX => '.pl');
    print {$child_fh} "print \"Hi! \\x{263a}\\n\";\n";
    close($child_fh);
    my $jperl = $^X eq 'jperl' ? './jperl' : $^X;
    open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
    open(STDOUT, '>&', $sys_fh) or die "redirect stdout: $!";
    my $system_status = system($jperl, $child_name);
    open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
    close($saved_stdout);
    unlink $child_name;
    diag("child jperl exited with status $system_status") if $system_status != 0;

    seek($sys_fh, 0, 0);
    my $system_out = do { local $/; <$sys_fh> };
    close($sys_fh);
    unlink $sys_name;

    is($system_out, "Hi! \x{263a}\n",
        'system output is not double-encoded through parent utf8 layer');
}

my ($unlink_fh, $unlink_name);
my $registered_tempfile = eval {
    ($unlink_fh, $unlink_name) = tempfile(UNLINK => 1);
    1;
};
ok($registered_tempfile,
    'File::Temp cleanup registration accepts function-call form');
ok(-e $unlink_name, 'File::Temp tempfile with UNLINK registers temp file');
close($unlink_fh);
