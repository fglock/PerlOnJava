use strict;
use warnings;
use Test::More tests => 7;

# Keep this compilation unit Unicode-sourced while individual eval sites
# independently enable or disable the utf8 pragma: é.

my $quoted_divide = pack('C*', 0x22, 0xC3, 0xB7, 0x22);
ok(!utf8::is_utf8($quoted_divide), 'fixture is an octet string');

{
    use utf8;
    my $value = eval $quoted_divide;
    is($@, '', 'UTF-8 byte source compiles under lexical use utf8');
    is(ord($value), 0xF7, 'lexical use utf8 decodes eval byte source');
}

{
    no utf8;
    my $value = eval $quoted_divide;
    is(ord($value), 0xC3, 'no utf8 retains byte-source interpretation');
}

{
    no utf8;
    my $source = pack('C*', unpack('C*', "use utf8; \"\x{C3}\x{B7}\""));
    my $value = eval $source;
    is(ord($value), 0xF7, 'use utf8 inside byte source decodes following text');
}

{
    no utf8;
    my @prefix = unpack('C*', 'my $text = "use utf8"; "');
    my $source = pack('C*', @prefix, 0xC3, 0xB7, 0x22);
    my $value = eval $source;
    is(ord($value), 0xC3, 'quoted use utf8 text does not activate the pragma');
}

{
    use utf8;
    my $malformed = pack('C*', 0x22, 0xFF, 0x22);
    local $SIG{__WARN__} = sub { };
    my $value = eval $malformed;
    ok(!defined($value) && $@ =~ /Malformed UTF-8 character/,
       'malformed UTF-8 byte source is fatal');
}
