use strict;
use File::Temp qw(tempfile);
use Test::More;

my ($wide_out, $wide_path) = tempfile();
binmode $wide_out, ':bytes' or die "binmode: $!";
my @wide_warnings;
{
    local $SIG{__WARN__} = sub { push @wide_warnings, @_ };
    print {$wide_out} chr(0x100);
}
like $wide_warnings[0] // '', qr/Wide character in print/,
    'wide byte-handle print warns without use warnings';
close $wide_out or die "close: $!";
unlink $wide_path or die "unlink: $!";

my ($quiet_out, $quiet_path) = tempfile();
binmode $quiet_out, ':bytes' or die "binmode: $!";
my @quiet_warnings;
{
    use warnings;
    no warnings 'utf8';
    local $SIG{__WARN__} = sub { push @quiet_warnings, @_ };
    print {$quiet_out} chr(0x100);
}
is scalar(@quiet_warnings), 0,
    'an explicit no warnings utf8 still suppresses the default warning';
close $quiet_out or die "close: $!";
unlink $quiet_path or die "unlink: $!";

my ($bad_out, $bad_path) = tempfile();
binmode $bad_out, ':raw' or die "binmode: $!";
print {$bad_out} "\xC4\xAC\x82\n" or die "write malformed input: $!";
close $bad_out or die "close: $!";

my $bad_in;
my $value;
open $bad_in, '<:utf8', $bad_path or die "open malformed input: $!";
$value = <$bad_in>;
chomp $value;
my @utf8_warnings;
{
    local $SIG{__WARN__} = sub { push @utf8_warnings, @_ };
    my $formatted = sprintf '%vd', $value;
}
is scalar(@utf8_warnings), 1,
    'malformed UTF-8 vector formatting warns without use warnings';
like $utf8_warnings[0] // '',
    qr/Malformed UTF-8 character: \\x82 \(unexpected continuation byte 0x82/,
    'malformed UTF-8 warning identifies the unexpected continuation';
my @muted_utf8_warnings;
{
    use warnings;
    no warnings 'utf8';
    local $SIG{__WARN__} = sub { push @muted_utf8_warnings, @_ };
    my $formatted = sprintf '%vd', $value;
}
is scalar(@muted_utf8_warnings), 0,
    'explicit no warnings utf8 suppresses the default malformed-byte warning';
close $bad_in or die "close: $!";
unlink $bad_path or die "unlink: $!";

done_testing();
