use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempfile);

our @format_warnings;
my ($capture_fh, $capture_path) = tempfile();
open my $saved_stdout, '>&STDOUT' or die "save STDOUT: $!";
open STDOUT, '>&', $capture_fh or die "redirect STDOUT: $!";

sub write_active_lexical ($) {
    my $test = $_[0];
    write;
format STDOUT =
ok @<<<<<<<
$test
.
}

{
    local $SIG{__WARN__} = sub { push @format_warnings, @_ };
    write_active_lexical(1);
    write_active_lexical(2);
}

open STDOUT, '>&', $saved_stdout or die "restore STDOUT: $!";
close $capture_fh or die "close format capture: $!";
open my $captured_output, '<', $capture_path or die "read format capture: $!";
local $/;
my $formatted = <$captured_output>;
close $captured_output;
unlink $capture_path or die "unlink format capture: $!";
like($formatted, qr/ok 1\s+ok 2\s*\z/,
    'a format evaluates its captured lexical while the declaring subroutine is active');
ok(!grep(/Variable ".*" is not available at format/, @format_warnings),
    'an active format lexical is not reported as unavailable');
