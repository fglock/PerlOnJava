use strict;
use warnings;

use Config;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'tests PerlOnJava bundled IO precedence during CPAN runs'
    unless $Config{archname} =~ /^java-/;

my $shadow = tempdir(CLEANUP => 1);
make_path("$shadow/IO");

for my $module (qw(Handle File)) {
    open my $fh, '>', "$shadow/IO/$module.pm"
        or die "Cannot create shadow IO::$module module: $!";
    print {$fh} "package IO::$module; 1;\n"
        or die "Cannot write shadow IO::$module module: $!";
    close $fh or die "Cannot close shadow IO::$module module: $!";
}

my $probe = "$shadow/probe.pl";
open my $probe_fh, '>', $probe or die "Cannot create IO shadow probe: $!";
print {$probe_fh} <<'PROBE';
use IO::Handle;
use IO::File;
print "handle=$INC{'IO/Handle.pm'}\n";
print "file=$INC{'IO/File.pm'}\n";
exit(IO::File->can('new_tmpfile') ? 0 : 1);
PROBE
close $probe_fh or die "Cannot close IO shadow probe: $!";

my $jperl = $ENV{PERLONJAVA_EXECUTABLE};
ok(defined($jperl) && -x $jperl, 'test runner exposes the jperl launcher');

sub shell_quote {
    my ($value) = @_;
    $value =~ s/'/'"'"'/g;
    return "'$value'";
}

my $output = "$shadow/probe.log";
my @command = (
    'timeout', '60', 'env',
    "PERL5LIB=$shadow",
    'PERLONJAVA_PREFER_BUNDLED_MODULES=IO/Handle.pm,IO/File.pm',
    $jperl, $probe,
);
my $command = join ' ', map { shell_quote($_) } @command;
my $status = system "$command > " . shell_quote($output) . ' 2>&1';
is($status, 0, 'bounded child jperl keeps bundled IO modules ahead of CPAN blib shadows');

open my $output_fh, '<', $output or die "Cannot read IO shadow probe output: $!";
my $probe_output = do { local $/; <$output_fh> };
close $output_fh or die "Cannot close IO shadow probe output: $!";
is($probe_output, "handle=jar:PERL5LIB/IO/Handle.pm\nfile=jar:PERL5LIB/IO/File.pm\n",
   'IO::Handle and IO::File both resolve to bundled sources');

done_testing;
