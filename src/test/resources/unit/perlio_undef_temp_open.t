use strict;
use warnings;
use Fcntl qw(SEEK_SET);

print "1..10\n";

sub check_file {
    my ($mode, $text, $label) = @_;
    my $open_ok = open my $fh, $mode, undef;
    print(($open_ok ? "ok" : "not ok"), " ", ++$::n, " - ", $label, " opens\n");
    return unless $open_ok;
    print $fh $text or die "write $mode: $!";
    my $seek_ok = seek($fh, 0, SEEK_SET);
    print(($seek_ok ? "ok" : "not ok"), " ", ++$::n, " - ", $label, " can seek\n");
    my $read = <$fh>;
    my $read_ok = defined($read) && $read eq $text;
    print(($read_ok ? "ok" : "not ok"), " ", ++$::n, " - ", $label, " reads written data\n");
}

$::n = 0;
check_file('+>', 'truncate mode', '+>');
check_file('+<', 'read/write mode', '+<');

my $append_open_ok = open my $append, '+>>', undef;
print(($append_open_ok ? "ok" : "not ok"), " ", ++$::n, " - +>> opens\n");
if ($append_open_ok) {
print $append 'first';
print $append 'second';
my $append_seek_ok = seek($append, 0, SEEK_SET);
print(($append_seek_ok ? "ok" : "not ok"), " ", ++$::n, " - +>> can seek\n");
my $append_data = <$append>;
print((defined($append_data) && $append_data eq 'firstsecond' ? "ok" : "not ok"), " ", ++$::n, " - +>> appends and reads\n");
}

my $wrapped_open_ok = open my $wrapped, '+>', undef;
print(($wrapped_open_ok ? "ok" : "not ok"), " ", ++$::n, " - undef-path handle can be assigned\n");
