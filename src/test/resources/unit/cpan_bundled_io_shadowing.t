use strict;
use warnings;

use Config;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'tests PerlOnJava bundled IO precedence during CPAN runs'
    unless $Config{archname} =~ /^java-/;

require PerlOnJava::Process;

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

local $ENV{PERL5LIB} = $shadow;
local $ENV{PERLONJAVA_PREFER_BUNDLED_MODULES} = 'IO/Handle.pm,IO/File.pm';
my $result = PerlOnJava::Process::run_process(
    argv => [ $jperl, $probe ],
    timeout => 60,
);
ok(!$result->{timed_out} && $result->{exit_code} == 0,
   'bounded child jperl keeps bundled IO modules ahead of CPAN blib shadows');

is($result->{output}, "handle=jar:PERL5LIB/IO/Handle.pm\nfile=jar:PERL5LIB/IO/File.pm\n",
   'IO::Handle and IO::File both resolve to bundled sources');

done_testing;
