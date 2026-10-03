use strict;
use warnings;
use File::Spec;

my $output_path = File::Spec->catfile(
    File::Spec->tmpdir, "perlonjava-debug-lexical-sub-goto-$$.txt",
);
my $program = <<'PERL';
open STDOUT, '>', $ARGV[0] or die $!;
open STDERR, '>&STDOUT' or die $!;
use feature qw(lexical_subs state);
no warnings 'experimental::lexical_subs';
sub DB::goto { print "4\n"; $_ = $DB::sub }
state sub foo { print "2\n" }
$^P |= 0x80;
sub { goto &foo }->();
print $_ == \&foo ? "ok\n" : "$_\n";
PERL

local $ENV{PERL5DB} = 'sub DB::DB{}';
my @command = (
    ($ENV{PERLONJAVA_EXECUTABLE} || $^X), '-d', '-e', $program, $output_path,
);
my $status = system @command;
open my $output, '<', $output_path or die "could not read debugger output: $!";
my $text = do { local $/; <$output> // '' };
close $output;
unlink $output_path;

my $ok = $status == 0 && $text eq "4\n2\nok\n";
print "1..1\n";
print "# child status=$status output=<$text>\n" unless $ok;
print(($ok ? 'ok' : 'not ok'), ' 1 - DB::goto preserves lexical sub references in $_', "\n");
