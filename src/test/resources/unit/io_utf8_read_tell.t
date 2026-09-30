use strict;
use warnings;
use bytes ();
use File::Temp qw(tempfile);
use Test::More;

my ($out, $path) = tempfile();
binmode $out, ':utf8' or die "binmode: $!";
my @characters = (chr(0), chr(1), chr(16), chr(256), chr(4096), chr(65536), chr(1048576));
print {$out} @characters or die "print: $!";
close $out or die "close: $!";

open my $in, '<:utf8', $path or die "open: $!";
my $expected_position = 0;
for my $character (@characters) {
    my $read = read $in, my $got, 1;
    is $read, 1, 'read returns one UTF-8 character';
    is $got, $character, 'read returns the requested character';
    $expected_position += bytes::length($got);
    is tell($in), $expected_position, 'tell reports consumed bytes, not decoder lookahead';
}
close $in or die "close: $!";
unlink $path or die "unlink: $!";

my ($bad_out, $bad_path) = tempfile();
binmode $bad_out, ':raw' or die "binmode: $!";
print {$bad_out} "\xE4\n" or die "print malformed input: $!";
close $bad_out or die "close malformed input: $!";

my @utf8_warnings;
{
    local $SIG{__WARN__} = sub { push @utf8_warnings, @_ };
    open my $bad_in, '<:utf8', $bad_path or die "open malformed input: $!";
    scalar <$bad_in>;
    close $bad_in or die "close malformed input: $!";
}
like $utf8_warnings[0] // '', qr/utf8 "\\xE4" does not map to Unicode/,
    'incomplete UTF-8 lead byte warns with Perl-compatible uppercase escape';
unlink $bad_path or die "unlink malformed input: $!";

my ($continuation_out, $continuation_path) = tempfile();
binmode $continuation_out, ':raw' or die "binmode: $!";
print {$continuation_out} "\x82\n" or die "print malformed continuation: $!";
close $continuation_out or die "close malformed continuation: $!";

my $continuation_in;
my @continuation_warnings;
my $continuation;
{
    local $SIG{__WARN__} = sub { };
    open $continuation_in, '<:utf8', $continuation_path
        or die "open malformed continuation: $!";
    $continuation = <$continuation_in>;
    chomp $continuation;
}
{
    local $SIG{__WARN__} = sub { push @continuation_warnings, @_ };
    my $formatted = sprintf '%vd', $continuation;
}
is scalar(@continuation_warnings), 1,
    'reading malformed UTF-8 defers one warning until the value is formatted';
like $continuation_warnings[0] // '',
    qr/Malformed UTF-8 character: \\x82 \(unexpected continuation byte 0x82/,
    'deferred malformed UTF-8 warning retains the offending byte';
close $continuation_in or die "close malformed continuation: $!";
unlink $continuation_path or die "unlink malformed continuation: $!";

my ($wide_out, $wide_path) = tempfile();
binmode $wide_out, ':bytes' or die "binmode: $!";
my @wide_warnings;
{
    local $SIG{__WARN__} = sub { push @wide_warnings, @_ };
    print {$wide_out} chr(0x100);
}
like $wide_warnings[0] // '', qr/Wide character in print/,
    'writing a wide character to a byte handle warns';
close $wide_out or die "close wide output: $!";
unlink $wide_path or die "unlink wide output: $!";

done_testing();
