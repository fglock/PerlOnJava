use strict;
use warnings;
my $output_path = "/tmp/perlonjava-debug-overload-db-sub-$$.txt";
my $program = <<'PERL';
open STDOUT, '>', $ARGV[0] or die $!;
open STDERR, '>&STDOUT' or die $!;
package DB;
sub DB::sub {
    our @count;
    if ($DB::sub eq 'version::(""') {
        print "OK\n";
        exit;
    }
    push @count, $DB::sub;
    die "Fail @count\n" if @count > 10;
    &$DB::sub;
}
package main;
sub f {
    my $v = "$^V";
}
f();
PERL
local $ENV{PERL5DB} = 'sub DB::DB {}';
my @command = (
    ($ENV{PERLONJAVA_EXECUTABLE} || $^X),
    '-Iperl5_t/t/lib', '-d', '-e', $program, $output_path,
);
my $status = system @command;
open my $output, '<', $output_path or die "could not read debugger output: $!";
my $text = do { local $/; <$output> // '' };
close $output;
unlink $output_path;
my $ok = $status == 0 && $text eq "OK\n";
print "1..1\n";
print "# child status=$status output=<$text>\n" unless $ok;
print(($ok ? 'ok' : 'not ok'),
    " 1 - overloaded method calls pass through debugger DB::sub\n");
