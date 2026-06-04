use strict;
use warnings;
use Test::More;
use Encode qw(decode FB_WARN);

sub decode_utf8 {
    my ($bytes) = @_;
    return decode('UTF-8', $bytes, FB_WARN());
}

my $nbsp = decode_utf8("\xc2\xa0");
my $nel  = decode_utf8("\xc2\x85");

ok(utf8::is_utf8($nbsp), 'decoded NBSP is UTF-8 flagged');
ok(utf8::is_utf8($nel),  'decoded NEL is UTF-8 flagged');

ok($nbsp =~ /\A\s\z/, 'Unicode \\s matches NBSP');
ok($nel  =~ /\A\s\z/, 'Unicode \\s matches NEL');
ok($nbsp !~ /\A\S\z/, 'Unicode \\S does not match NBSP');
ok($nel  !~ /\A\S\z/, 'Unicode \\S does not match NEL');

ok($nbsp =~ /\A[\s]\z/, 'bracketed Unicode \\s matches NBSP');
ok($nbsp !~ /\A[\S]\z/, 'bracketed Unicode \\S does not match NBSP');

my $text = decode_utf8("\xc2\xa0\xc2\xa0\tFoo Bar Baz\xc2\xa0\t\r\n");
my $trimmed = $text;
$trimmed =~ s/\A\s+//;
$trimmed =~ s/\s+\z//;

is($trimmed, 'Foo Bar Baz', 'Unicode \\s trims decoded NBSP at string edges');

done_testing();
